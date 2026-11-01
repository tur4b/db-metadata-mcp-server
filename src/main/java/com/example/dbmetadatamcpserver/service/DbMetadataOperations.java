package com.example.dbmetadatamcpserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@RequiredArgsConstructor
@Service
public class DbMetadataOperations {

    public static final String COLUMN_TABLE_SCHEMA = "TABLE_SCHEM";
    public static final String COLUMN_REMARKS = "REMARKS";
    public static final String COLUMN_TABLE_TYPE = "TABLE_TYPE";
    public static final String COLUMN_TABLE_NAME = "TABLE_NAME";
    public static final String COLUMN_NAME = "COLUMN_NAME";
    public static final String COLUMN_DEF = "COLUMN_DEF";
    public static final String COLUMN_SIZE = "COLUMN_SIZE";
    public static final String COLUMN_TYPE_NAME = "TYPE_NAME";
    public static final String COLUMN_IS_NULLABLE = "IS_NULLABLE";
    public static final String COLUMN_INDEX_NAME = "INDEX_NAME";
    public static final String COLUMN_ORDINAL_POSITION = "ORDINAL_POSITION";
    public static final String COLUMN_NON_UNIQUE = "NON_UNIQUE";
    public static final String COLUMN_TABLE_CAT = "TABLE_CAT";

    private static final int MAX_RESULTS = 100;

    private final DbMetadataUtils dbMetadataUtils;
    private final JdbcTemplate jdbcTemplate;

    public List<String> listSchemas() {
        return dbMetadataUtils.meta(md -> {
            List<String> out = new ArrayList<>();
            try (ResultSet rs = md.getSchemas()) {
                while (rs.next()) out.add(rs.getString(COLUMN_TABLE_SCHEMA));
            }
            return out;
        });
    }

    public List<DbMetadataUtils.TableInfo> listTables(String schema, String namePattern) {
        return dbMetadataUtils.meta(md -> {
            List<DbMetadataUtils.TableInfo> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(null, schema, namePattern, new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    out.add(new DbMetadataUtils.TableInfo(rs.getString(COLUMN_TABLE_SCHEMA), rs.getString(COLUMN_TABLE_NAME),
                            rs.getString(COLUMN_TABLE_TYPE), rs.getString(COLUMN_REMARKS)));
                }
            }
            return out;
        });
    }

    public List<DbMetadataUtils.ColumnInfo> describeTable(String schema, String table) {
        return dbMetadataUtils.meta(md -> {
            Set<String> pk = new HashSet<>();
            try (ResultSet rs = md.getPrimaryKeys(null, schema, table)) {
                while (rs.next()) pk.add(rs.getString(COLUMN_NAME));
            }
            List<DbMetadataUtils.ColumnInfo> out = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, schema, table, "%")) {
                while (rs.next()) {
                    // table name is a pattern in JDBC ('_' is a wildcard), so enforce an exact match
                    if (!table.equalsIgnoreCase(rs.getString(COLUMN_TABLE_NAME))) continue;
                    String col = rs.getString(COLUMN_NAME);
                    out.add(new DbMetadataUtils.ColumnInfo(col, rs.getString(COLUMN_TYPE_NAME), rs.getInt(COLUMN_SIZE),
                            "YES".equals(rs.getString(COLUMN_IS_NULLABLE)), rs.getString(COLUMN_DEF),
                            pk.contains(col), rs.getString(COLUMN_REMARKS)));
                }
            }
            if (out.isEmpty()) throw new IllegalArgumentException("Table not found: " + table);
            return out;
        });
    }

    public List<DbMetadataUtils.IndexInfo> listIndexes(String schema, String table) {
        return dbMetadataUtils.meta(md -> {
            List<DbMetadataUtils.IndexInfo> out = new ArrayList<>();
            try (ResultSet rs = md.getIndexInfo(null, schema, table, false, false)) {
                while (rs.next()) {
                    String name = rs.getString(COLUMN_INDEX_NAME);
                    if (name == null) continue; // table statistics row
                    out.add(new DbMetadataUtils.IndexInfo(name, rs.getString(COLUMN_NAME),
                            !rs.getBoolean(COLUMN_NON_UNIQUE), rs.getInt(COLUMN_ORDINAL_POSITION)));
                }
            }
            return out;
        });
    }

    public List<DbMetadataUtils.ForeignKey> listForeignKeys(String schema, String table) {
        return dbMetadataUtils.meta(md -> {
            List<DbMetadataUtils.ForeignKey> out = new ArrayList<>();
            try (ResultSet rs = md.getImportedKeys(null, schema, table)) {
                while (rs.next() && out.size() < MAX_RESULTS) out.add(toFk(rs, "OUTGOING"));
            }
            try (ResultSet rs = md.getExportedKeys(null, schema, table)) {
                while (rs.next() && out.size() < MAX_RESULTS) out.add(toFk(rs, "INCOMING"));
            }
            return out;
        });
    }

    public List<DbMetadataUtils.ColumnMatch> searchColumns(String schema, String columnPattern) {
        return dbMetadataUtils.meta(md -> {
            List<DbMetadataUtils.ColumnMatch> out = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, schema, "%", columnPattern)) {
                while (rs.next() && out.size() < MAX_RESULTS) {
                    out.add(new DbMetadataUtils.ColumnMatch(rs.getString(COLUMN_TABLE_SCHEMA), rs.getString(COLUMN_TABLE_NAME),
                            rs.getString(COLUMN_NAME), rs.getString(COLUMN_TYPE_NAME)));
                }
            }
            return out;
        });
    }

    public DbMetadataUtils.EntityTableInfo getTableEntityInfo(String schema, String table) {
        return dbMetadataUtils.meta(md -> {
            // 1. Resolve the real table (getTables treats the name as a pattern)
            String realSchema = null, realTable = null, tableType = null, remarks = null;
            try (ResultSet rs = md.getTables(null, schema, table, new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    if (table.equalsIgnoreCase(rs.getString("TABLE_NAME"))) {
                        realSchema = rs.getString("TABLE_SCHEM");
                        realTable = rs.getString("TABLE_NAME");
                        tableType = rs.getString("TABLE_TYPE");
                        remarks = rs.getString("REMARKS");
                        break;
                    }
                }
            }
            if (realTable == null) throw new IllegalArgumentException("Table not found: " + table);

            List<String> warnings = new ArrayList<>();

            // 2. Columns
            List<DbMetadataUtils.EntityColumn> columns = new ArrayList<>();
            try (ResultSet rs = md.getColumns(null, realSchema, realTable, "%")) {
                while (rs.next()) {
                    if (!realTable.equals(rs.getString("TABLE_NAME"))) continue; // pattern guard
                    int sqlType = rs.getInt("DATA_TYPE");
                    int scale = rs.getInt("DECIMAL_DIGITS");
                    Integer scaleOrNull = rs.wasNull() ? null : scale;
                    String generated = null;
                    try { generated = rs.getString("IS_GENERATEDCOLUMN"); } catch (SQLException ignored) { }
                    columns.add(new DbMetadataUtils.EntityColumn(
                            rs.getInt("ORDINAL_POSITION"),
                            rs.getString("COLUMN_NAME"),
                            sqlTypeName(sqlType),
                            rs.getString("TYPE_NAME"),
                            rs.getInt("COLUMN_SIZE"),
                            scaleOrNull,
                            "YES".equals(rs.getString("IS_NULLABLE")),
                            rs.getString("COLUMN_DEF"),
                            "YES".equals(rs.getString("IS_AUTOINCREMENT")),
                            "YES".equals(generated),
                            rs.getString("REMARKS")));
                    if (sqlType == Types.OTHER || sqlType == Types.ARRAY
                            || sqlType == Types.STRUCT || sqlType == Types.DISTINCT) {
                        warnings.add("Column '" + rs.getString("COLUMN_NAME")
                                + "' has a driver-specific type (" + rs.getString("TYPE_NAME")
                                + "); the Java mapping needs a manual decision.");
                    }
                }
            }
            if (columns.size() > MAX_RESULTS) throw new IllegalArgumentException("Too many columns (max " + MAX_RESULTS + ")");
            columns.sort(Comparator.comparingInt(DbMetadataUtils.EntityColumn::position));

            // 3. Primary key, ordered by KEY_SEQ
            TreeMap<Integer, String> pk = new TreeMap<>();
            String pkName = null;
            try (ResultSet rs = md.getPrimaryKeys(null, realSchema, realTable)) {
                while (rs.next()) {
                    pk.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
                    pkName = rs.getString("PK_NAME");
                }
            }
            if (pk.isEmpty()) {
                warnings.add("No primary key found" + ("VIEW".equals(tableType)
                        ? " (this is a view)." : ". JPA requires an @Id; choose a column or composite key."));
            } else if (pk.size() > 1) {
                warnings.add("Composite primary key; use @IdClass or @EmbeddedId.");
            }

            // 4. Foreign keys. Rows for one FK arrive in KEY_SEQ order per the JDBC spec.
            List<DbMetadataUtils.EntityForeignKey> outgoing;
            try (ResultSet rs = md.getImportedKeys(null, realSchema, realTable)) {
                outgoing = readForeignKeys(rs);
            }
            List<DbMetadataUtils.EntityForeignKey> incoming;
            try (ResultSet rs = md.getExportedKeys(null, realSchema, realTable)) {
                incoming = readForeignKeys(rs);
            }

            // 5. Indexes, grouped by name (rows arrive ordered by ORDINAL_POSITION within an index)
            Map<String, boolean[]> uniqueFlags = new LinkedHashMap<>();
            Map<String, List<String>> indexColumns = new LinkedHashMap<>();
            try (ResultSet rs = md.getIndexInfo(null, realSchema, realTable, false, false)) {
                while (rs.next()) {
                    String name = rs.getString("INDEX_NAME");
                    String col = rs.getString("COLUMN_NAME");
                    if (name == null) continue; // table statistics row
                    uniqueFlags.putIfAbsent(name, new boolean[]{!rs.getBoolean("NON_UNIQUE")});
                    indexColumns.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(col != null ? col : "(expression)");
                }
            }
            List<DbMetadataUtils.EntityIndex> indexes = new ArrayList<>();
            indexColumns.forEach((name, cols) ->
                    indexes.add(new DbMetadataUtils.EntityIndex(name, uniqueFlags.get(name)[0], cols)));

            return new DbMetadataUtils.EntityTableInfo(
                    md.getDatabaseProductName(), md.getDatabaseProductVersion(),
                    realSchema, realTable, tableType, remarks,
                    columns, new ArrayList<>(pk.values()), pkName,
                    outgoing, incoming, indexes, warnings);
        });
    }

    public List<DbMetadataUtils.JoinStep> findJoinPath(String schema, String fromTable, String toTable, int depthLimit) {

        record Node(String table, int depth) {}

        return dbMetadataUtils.meta(md -> {
            Map<String, DbMetadataUtils.JoinStep> reachedBy = new HashMap<>();   // lowercase table -> step that reached it
            Set<String> visited = new HashSet<>();
            Deque<Node> queue = new ArrayDeque<>();
            queue.add(new Node(fromTable, 0));
            visited.add(fromTable.toLowerCase());

            while (!queue.isEmpty()) {
                Node cur = queue.poll();

                if (cur.table().equalsIgnoreCase(toTable)) {
                    LinkedList<DbMetadataUtils.JoinStep> path = new LinkedList<>();
                    String t = cur.table();
                    while (!t.equalsIgnoreCase(fromTable)) {
                        DbMetadataUtils.JoinStep s = reachedBy.get(t.toLowerCase());
                        path.addFirst(s);
                        t = s.fromTable();
                    }
                    return path;
                }
                if (cur.depth() >= depthLimit) continue;

                List<DbMetadataUtils.EntityForeignKey> fks = new ArrayList<>();
                try (ResultSet rs = md.getImportedKeys(null, schema, cur.table())) {
                    fks.addAll(readForeignKeys(rs));
                }
                try (ResultSet rs = md.getExportedKeys(null, schema, cur.table())) {
                    fks.addAll(readForeignKeys(rs));
                }

                for (DbMetadataUtils.EntityForeignKey fk : fks) {
                    boolean outgoing = fk.fkTable().equalsIgnoreCase(cur.table());
                    String next = outgoing ? fk.pkTable() : fk.fkTable();
                    if (!visited.add(next.toLowerCase())) continue;   // also skips self-references

                    StringJoiner cond = new StringJoiner(" AND ");
                    for (int i = 0; i < fk.fkColumns().size(); i++) {
                        cond.add(fk.fkTable() + "." + fk.fkColumns().get(i)
                                + " = " + fk.pkTable() + "." + fk.pkColumns().get(i));
                    }
                    reachedBy.put(next.toLowerCase(),
                            new DbMetadataUtils.JoinStep(cur.table(), next, fk.name(), cond.toString()));
                    queue.add(new Node(next, cur.depth() + 1));
                }
            }
            return List.of();
        });
    }

    public List<DbMetadataUtils.ViewInfo> listViews(String schema, String namePattern) {
        return jdbcTemplate.execute((ConnectionCallback<List<DbMetadataUtils.ViewInfo>>) con -> {
            DatabaseMetaData meta = con.getMetaData();
            List<DbMetadataUtils.ViewInfo> views = new ArrayList<>();

            try (ResultSet rs = meta.getTables(
                    con.getCatalog(),          // scope to the current database
                    emptyToNull(schema),
                    emptyToNull(namePattern),
                    new String[] { "VIEW" })) {

                while (rs.next()) {
                    views.add(new DbMetadataUtils.ViewInfo(
                            rs.getString(COLUMN_TABLE_CAT),
                            rs.getString(COLUMN_TABLE_SCHEMA),
                            rs.getString(COLUMN_TABLE_NAME),
                            rs.getString(COLUMN_REMARKS)));
                }
            }
            return views;
        });
    }

    public DbMetadataUtils.RolesAndGrants listRolesAndGrants(String roleName) {

        String roleFilter = roleName == null || roleName.isBlank()
                ? null
                : roleName.trim();

        // 1. Roles
        String rolesSql = """
        SELECT
            rolname,
            rolsuper,
            rolinherit,
            rolcreaterole,
            rolcreatedb,
            rolcanlogin,
            rolreplication,
            rolbypassrls,
            rolconnlimit,
            rolvaliduntil
        FROM pg_roles
        WHERE (CAST(? AS TEXT) IS NULL OR rolname = ?)
        ORDER BY rolname
        """;

        List<DbMetadataUtils.DatabaseRole> roles = jdbcTemplate.query(
                rolesSql,
                (rs, rowNum) -> new DbMetadataUtils.DatabaseRole(
                        rs.getString("rolname"),
                        rs.getBoolean("rolsuper"),
                        rs.getBoolean("rolinherit"),
                        rs.getBoolean("rolcreaterole"),
                        rs.getBoolean("rolcreatedb"),
                        rs.getBoolean("rolcanlogin"),
                        rs.getBoolean("rolreplication"),
                        rs.getBoolean("rolbypassrls"),
                        rs.getInt("rolconnlimit"),
                        rs.getString("rolvaliduntil")
                ),
                roleFilter,
                roleFilter
        );

        // 2. Role memberships
        String membershipsSql = """
        SELECT
            member.rolname AS member,
            granted.rolname AS granted_role,
            grantor.rolname AS grantor,
            membership.admin_option
        FROM pg_auth_members membership
        JOIN pg_roles member
            ON member.oid = membership.member
        JOIN pg_roles granted
            ON granted.oid = membership.roleid
        JOIN pg_roles grantor
            ON grantor.oid = membership.grantor
        WHERE (
            CAST(? AS TEXT) IS NULL
            OR member.rolname = ?
            OR granted.rolname = ?
        )
        ORDER BY granted.rolname
        """;

        List<DbMetadataUtils.RoleMembership> memberships = jdbcTemplate.query(
                membershipsSql,
                (rs, rowNum) -> new DbMetadataUtils.RoleMembership(
                        rs.getString("member"),
                        rs.getString("granted_role"),
                        rs.getString("grantor"),
                        rs.getBoolean("admin_option")
                ),
                roleFilter,
                roleFilter,
                roleFilter
        );

        // 3. Database privileges
        // JOIN LATERAL ... ON d.datacl IS NOT NULL skips databases with no explicit
        // ACL (avoids acldefault() which requires PG14+ and COALESCE(x, '{}'::aclitem[])
        // which causes "ACL arrays must be one-dimensional" on older versions).
        String databaseGrantsSql = """
        SELECT
            d.datname AS object_name,
            owner.rolname AS owner,
            CASE
                WHEN acl.grantee = 0 THEN 'PUBLIC'
                ELSE grantee.rolname
            END AS grantee,
            acl.privilege_type AS privilege,
            acl.is_grantable AS grantable,
            grantor.rolname AS grantor
        FROM pg_database d
        JOIN pg_roles owner
            ON owner.oid = d.datdba
        JOIN LATERAL aclexplode(d.datacl) acl
            ON d.datacl IS NOT NULL
        LEFT JOIN pg_roles grantee
            ON grantee.oid = acl.grantee
        LEFT JOIN pg_roles grantor
            ON grantor.oid = acl.grantor
        WHERE d.datname = current_database()
          AND (
              CAST(? AS TEXT) IS NULL
              OR grantee.rolname = ?
              OR acl.grantee = 0
          )
        ORDER BY grantee, acl.privilege_type
        """;

        List<DbMetadataUtils.PrivilegeGrant> databaseGrants = jdbcTemplate.query(
                databaseGrantsSql,
                (rs, rowNum) -> new DbMetadataUtils.PrivilegeGrant(
                        "DATABASE",
                        rs.getString("object_name"),
                        rs.getString("owner"),
                        rs.getString("grantee"),
                        rs.getString("privilege"),
                        rs.getBoolean("grantable"),
                        rs.getString("grantor")
                ),
                roleFilter,
                roleFilter
        );

        // 4. Schema privileges
        String schemaGrantsSql = """
        SELECT
            n.nspname AS object_name,
            owner.rolname AS owner,
            CASE
                WHEN acl.grantee = 0 THEN 'PUBLIC'
                ELSE grantee.rolname
            END AS grantee,
            acl.privilege_type AS privilege,
            acl.is_grantable AS grantable,
            grantor.rolname AS grantor
        FROM pg_namespace n
        JOIN pg_roles owner
            ON owner.oid = n.nspowner
        JOIN LATERAL aclexplode(n.nspacl) acl
            ON n.nspacl IS NOT NULL
        LEFT JOIN pg_roles grantee
            ON grantee.oid = acl.grantee
        LEFT JOIN pg_roles grantor
            ON grantor.oid = acl.grantor
        WHERE n.nspname NOT IN ('information_schema')
          AND n.nspname NOT LIKE 'pg_%'
          AND (
              CAST(? AS TEXT) IS NULL
              OR grantee.rolname = ?
              OR acl.grantee = 0
          )
        ORDER BY n.nspname, grantee, acl.privilege_type
        """;

        List<DbMetadataUtils.PrivilegeGrant> schemaGrants = jdbcTemplate.query(
                schemaGrantsSql,
                (rs, rowNum) -> new DbMetadataUtils.PrivilegeGrant(
                        "SCHEMA",
                        rs.getString("object_name"),
                        rs.getString("owner"),
                        rs.getString("grantee"),
                        rs.getString("privilege"),
                        rs.getBoolean("grantable"),
                        rs.getString("grantor")
                ),
                roleFilter,
                roleFilter
        );

        // 5. Table, view and sequence privileges
        String objectGrantsSql = """
        SELECT
            n.nspname || '.' || c.relname AS object_name,
            owner.rolname AS owner,
            CASE
                WHEN acl.grantee = 0 THEN 'PUBLIC'
                ELSE grantee.rolname
            END AS grantee,
            acl.privilege_type AS privilege,
            acl.is_grantable AS grantable,
            grantor.rolname AS grantor,
            CASE
                WHEN c.relkind = 'S' THEN 'SEQUENCE'
                ELSE 'TABLE'
            END AS object_type
        FROM pg_class c
        JOIN pg_namespace n
            ON n.oid = c.relnamespace
        JOIN pg_roles owner
            ON owner.oid = c.relowner
        JOIN LATERAL aclexplode(c.relacl) acl
            ON c.relacl IS NOT NULL
        LEFT JOIN pg_roles grantee
            ON grantee.oid = acl.grantee
        LEFT JOIN pg_roles grantor
            ON grantor.oid = acl.grantor
        WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
          AND n.nspname NOT IN ('information_schema')
          AND n.nspname NOT LIKE 'pg_%'
          AND (
              CAST(? AS TEXT) IS NULL
              OR grantee.rolname = ?
              OR acl.grantee = 0
          )
        ORDER BY n.nspname, c.relname, grantee, acl.privilege_type
        """;

        List<DbMetadataUtils.PrivilegeGrant> objectGrants = jdbcTemplate.query(
                objectGrantsSql,
                (rs, rowNum) -> new DbMetadataUtils.PrivilegeGrant(
                        rs.getString("object_type"),
                        rs.getString("object_name"),
                        rs.getString("owner"),
                        rs.getString("grantee"),
                        rs.getString("privilege"),
                        rs.getBoolean("grantable"),
                        rs.getString("grantor")
                ),
                roleFilter,
                roleFilter
        );

        return new DbMetadataUtils.RolesAndGrants(
                jdbcTemplate.queryForObject(
                        "SELECT current_database()",
                        String.class
                ),
                roles,
                memberships,
                databaseGrants,
                schemaGrants,
                objectGrants
        );
    }

    public DbMetadataUtils.RoleDetails getRoleDetails(String roleName) {

        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("Role name is required");
        }

        String name = roleName.trim();

        // 1. Role attributes
        String roleSql = """
        SELECT
            rolname,
            rolsuper,
            rolinherit,
            rolcreaterole,
            rolcreatedb,
            rolcanlogin,
            rolreplication,
            rolbypassrls,
            rolconnlimit,
            rolvaliduntil
        FROM pg_roles
        WHERE rolname = ?
        """;

        List<DbMetadataUtils.DatabaseRole> roles = jdbcTemplate.query(
                roleSql,
                (rs, rowNum) -> new DbMetadataUtils.DatabaseRole(
                        rs.getString("rolname"),
                        rs.getBoolean("rolsuper"),
                        rs.getBoolean("rolinherit"),
                        rs.getBoolean("rolcreaterole"),
                        rs.getBoolean("rolcreatedb"),
                        rs.getBoolean("rolcanlogin"),
                        rs.getBoolean("rolreplication"),
                        rs.getBoolean("rolbypassrls"),
                        rs.getInt("rolconnlimit"),
                        rs.getString("rolvaliduntil")
                ),
                name
        );

        if (roles.isEmpty()) {
            throw new IllegalArgumentException("Role not found: " + name);
        }

        DbMetadataUtils.DatabaseRole role = roles.get(0);

        // 2. Roles granted TO this role
        String membershipsSql = """
    SELECT
        member.rolname AS member,
        granted.rolname AS granted_role,
        grantor.rolname AS grantor,
        membership.admin_option
    FROM pg_auth_members membership
    JOIN pg_roles member
        ON member.oid = membership.member
    JOIN pg_roles granted
        ON granted.oid = membership.roleid
    JOIN pg_roles grantor
        ON grantor.oid = membership.grantor
    WHERE member.rolname = ?
    ORDER BY granted.rolname
    """;

        List<DbMetadataUtils.RoleMembership> memberships =
                jdbcTemplate.query(
                        membershipsSql,
                        (rs, rowNum) -> new DbMetadataUtils.RoleMembership(
                                rs.getString("member"),
                                rs.getString("granted_role"),
                                rs.getString("grantor"),
                                rs.getBoolean("admin_option")
                        ),
                        name
                );

        // 3. Roles granted BY this role
        String grantedMembershipsSql = """
    SELECT
        member.rolname AS member,
        granted.rolname AS granted_role,
        grantor.rolname AS grantor,
        membership.admin_option
    FROM pg_auth_members membership
    JOIN pg_roles member
        ON member.oid = membership.member
    JOIN pg_roles granted
        ON granted.oid = membership.roleid
    JOIN pg_roles grantor
        ON grantor.oid = membership.grantor
    WHERE granted.rolname = ?
    """;

        List<DbMetadataUtils.RoleMembership> grantedMemberships =
                jdbcTemplate.query(
                        grantedMembershipsSql,
                        (rs, rowNum) -> new DbMetadataUtils.RoleMembership(
                                rs.getString("member"),
                                rs.getString("granted_role"),
                                rs.getString("grantor"),
                                rs.getBoolean("admin_option")
                        ),
                        name
                );

        // 4. Database privileges
        String databaseGrantsSql = """
    SELECT
        d.datname AS object_name,
        owner.rolname AS owner,
        CASE
            WHEN acl.grantee = 0 THEN 'PUBLIC'
            ELSE grantee.rolname
        END AS grantee,
        acl.privilege_type AS privilege,
        acl.is_grantable AS grantable,
        grantor.rolname AS grantor,
        'DATABASE' AS object_type
    FROM pg_database d
    JOIN pg_roles owner ON owner.oid = d.datdba
    JOIN LATERAL aclexplode(d.datacl) acl
        ON d.datacl IS NOT NULL
    LEFT JOIN pg_roles grantee ON grantee.oid = acl.grantee
    LEFT JOIN pg_roles grantor ON grantor.oid = acl.grantor
    WHERE d.datname = current_database()
      AND (grantee.rolname = ? OR acl.grantee = 0)
    ORDER BY d.datname, acl.privilege_type
    """;

        List<DbMetadataUtils.PrivilegeGrant> databaseGrants =
                queryGrants(databaseGrantsSql, name);

        // 5. Schema privileges
        String schemaGrantsSql = """
    SELECT
        n.nspname AS object_name,
        owner.rolname AS owner,
        CASE
            WHEN acl.grantee = 0 THEN 'PUBLIC'
            ELSE grantee.rolname
        END AS grantee,
        acl.privilege_type AS privilege,
        acl.is_grantable AS grantable,
        grantor.rolname AS grantor,
        'SCHEMA' AS object_type
    FROM pg_namespace n
    JOIN pg_roles owner ON owner.oid = n.nspowner
    JOIN LATERAL aclexplode(n.nspacl) acl
        ON n.nspacl IS NOT NULL
    LEFT JOIN pg_roles grantee ON grantee.oid = acl.grantee
    LEFT JOIN pg_roles grantor ON grantor.oid = acl.grantor
    WHERE n.nspname NOT IN ('information_schema')
      AND n.nspname NOT LIKE 'pg_%'
      AND (grantee.rolname = ? OR acl.grantee = 0)
    ORDER BY n.nspname, acl.privilege_type
    """;

        List<DbMetadataUtils.PrivilegeGrant> schemaGrants =
                queryGrants(schemaGrantsSql, name);

        // 6. Table, view and sequence privileges
        String objectGrantsSql = """
    SELECT
        n.nspname || '.' || c.relname AS object_name,
        owner.rolname AS owner,
        CASE
            WHEN acl.grantee = 0 THEN 'PUBLIC'
            ELSE grantee.rolname
        END AS grantee,
        acl.privilege_type AS privilege,
        acl.is_grantable AS grantable,
        grantor.rolname AS grantor,
        CASE
            WHEN c.relkind = 'S' THEN 'SEQUENCE'
            ELSE 'TABLE'
        END AS object_type
    FROM pg_class c
    JOIN pg_namespace n ON n.oid = c.relnamespace
    JOIN pg_roles owner ON owner.oid = c.relowner
    JOIN LATERAL aclexplode(c.relacl) acl
        ON c.relacl IS NOT NULL
    LEFT JOIN pg_roles grantee ON grantee.oid = acl.grantee
    LEFT JOIN pg_roles grantor ON grantor.oid = acl.grantor
    WHERE c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')
      AND n.nspname NOT IN ('information_schema')
      AND n.nspname NOT LIKE 'pg_%'
      AND (grantee.rolname = ? OR acl.grantee = 0)
    ORDER BY object_name, acl.privilege_type
    """;

        List<DbMetadataUtils.PrivilegeGrant> objectGrants =
                queryGrants(objectGrantsSql, name);

        return new DbMetadataUtils.RoleDetails(
                role,
                memberships,
                grantedMemberships,
                databaseGrants,
                schemaGrants,
                objectGrants
        );
    }

    /**
     * Lists privileges for a specific table, view, sequence, or schema.
     *
     * @param objectName  fully-qualified table/view/sequence (schema.object)
     *                    OR just a schema name — e.g. "public" or "public.users"
     * @param objectType  "TABLE" | "SCHEMA" | "SEQUENCE" | null (auto-detect)
     */
    public List<DbMetadataUtils.PrivilegeGrant> listObjectPrivileges(String objectName, String objectType) {

        if (objectName == null || objectName.isBlank()) {
            throw new IllegalArgumentException("Object name is required");
        }

        String name = objectName.trim();

        // Auto-detect: if no dot → treat as schema; if dot → table/view/seq
        if (objectType == null || objectType.isBlank()) {
            objectType = name.contains(".") ? "TABLE" : "SCHEMA";
        }

        return switch (objectType.toUpperCase()) {
            case "SCHEMA"   -> fetchSchemaPrivileges(name);
            case "TABLE",
                 "VIEW",
                 "SEQUENCE" -> fetchObjectPrivileges(name, objectType.toUpperCase());
            default -> throw new IllegalArgumentException(
                    "Unsupported object type: " + objectType +
                            ". Expected TABLE, VIEW, SEQUENCE, or SCHEMA.");
        };
    }

    // helper methods

    // Schema privileges
    private List<DbMetadataUtils.PrivilegeGrant> fetchSchemaPrivileges(String schemaName) {

        String sql = """
        SELECT
            n.nspname          AS object_name,
            owner.rolname      AS owner,
            CASE
                WHEN acl.grantee = 0 THEN 'PUBLIC'
                ELSE grantee.rolname
            END                AS grantee,
            acl.privilege_type AS privilege,
            acl.is_grantable   AS grantable,
            grantor.rolname    AS grantor,
            'SCHEMA'           AS object_type
        FROM pg_namespace n
        JOIN pg_roles owner   ON owner.oid   = n.nspowner
        JOIN LATERAL aclexplode(n.nspacl) acl
            ON n.nspacl IS NOT NULL
        LEFT JOIN pg_roles grantee  ON grantee.oid  = acl.grantee
        LEFT JOIN pg_roles grantor  ON grantor.oid  = acl.grantor
        WHERE n.nspname = ?
        ORDER BY acl.privilege_type, grantee
        """;

        return queryGrants(sql, schemaName);
    }

    // Table / view / sequence privileges
    private List<DbMetadataUtils.PrivilegeGrant> fetchObjectPrivileges(String fullyQualifiedName, String objectType) {

        // Split "schema.object" → schema + object
        String[] parts = fullyQualifiedName.split("\\.", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Table/view/sequence name must be fully qualified: schema.object_name");
        }

        String schemaName = parts[0];
        String relName    = parts[1];

        // relkind filter per type
        String relkindFilter = switch (objectType) {
            case "SEQUENCE" -> "c.relkind = 'S'";
            case "VIEW"     -> "c.relkind IN ('v', 'm')";   // view + materialized view
            default         -> "c.relkind IN ('r', 'p', 'f')"; // regular, partitioned, foreign
        };

        String sql = """
        SELECT
            n.nspname || '.' || c.relname  AS object_name,
            owner.rolname                  AS owner,
            CASE
                WHEN acl.grantee = 0 THEN 'PUBLIC'
                ELSE grantee.rolname
            END                            AS grantee,
            acl.privilege_type             AS privilege,
            acl.is_grantable               AS grantable,
            grantor.rolname                AS grantor,
            CASE c.relkind
                WHEN 'S'              THEN 'SEQUENCE'
                WHEN 'v'              THEN 'VIEW'
                WHEN 'm'              THEN 'VIEW'
                ELSE                  'TABLE'
            END                       AS object_type
        FROM pg_class c
        JOIN pg_namespace n    ON n.oid   = c.relnamespace
        JOIN pg_roles owner    ON owner.oid = c.relowner
        JOIN LATERAL aclexplode(c.relacl) acl
            ON c.relacl IS NOT NULL
        LEFT JOIN pg_roles grantee ON grantee.oid = acl.grantee
        LEFT JOIN pg_roles grantor ON grantor.oid = acl.grantor
        WHERE n.nspname = ?
          AND c.relname = ?
          AND\s""" + relkindFilter + """
    
        ORDER BY acl.privilege_type, grantee
        """;

        return queryGrants(sql, schemaName, relName);
    }

    private List<DbMetadataUtils.PrivilegeGrant> queryGrants(String sql, Object... args) {
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DbMetadataUtils.PrivilegeGrant(
                        rs.getString("object_name"),
                        rs.getString("object_type"),
                        rs.getString("owner"),
                        rs.getString("grantee"),
                        rs.getString("privilege"),
                        rs.getBoolean("grantable"),
                        rs.getString("grantor")
                ),
                args
        );
    }

    private static DbMetadataUtils.ForeignKey toFk(ResultSet rs, String direction) throws SQLException {
        return new DbMetadataUtils.ForeignKey(rs.getString("FK_NAME"), rs.getString("FKTABLE_NAME"),
                rs.getString("FKCOLUMN_NAME"), rs.getString("PKTABLE_NAME"),
                rs.getString("PKCOLUMN_NAME"), direction);
    }

    private static List<DbMetadataUtils.EntityForeignKey> readForeignKeys(ResultSet rs) throws SQLException {
        Map<String, DbMetadataUtils.EntityForeignKey> grouped = new LinkedHashMap<>();
        while (rs.next()) {
            String fkName = rs.getString("FK_NAME");
            String fkTable = rs.getString("FKTABLE_NAME");
            String pkTable = rs.getString("PKTABLE_NAME");
            String key = fkName + "|" + fkTable + "|" + pkTable;
            DbMetadataUtils.EntityForeignKey fk = grouped.computeIfAbsent(key, k -> {
                try {
                    return new DbMetadataUtils.EntityForeignKey(fkName,
                            rs.getString("FKTABLE_SCHEM"), fkTable, new ArrayList<>(),
                            rs.getString("PKTABLE_SCHEM"), pkTable, new ArrayList<>(),
                            rule(rs.getInt("UPDATE_RULE")), rule(rs.getInt("DELETE_RULE")));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            fk.fkColumns().add(rs.getString("FKCOLUMN_NAME"));
            fk.pkColumns().add(rs.getString("PKCOLUMN_NAME"));
        }
        return new ArrayList<>(grouped.values());
    }

    private static String rule(int r) {
        return switch (r) {
            case DatabaseMetaData.importedKeyCascade -> "CASCADE";
            case DatabaseMetaData.importedKeySetNull -> "SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> "SET DEFAULT";
            case DatabaseMetaData.importedKeyRestrict -> "RESTRICT";
            case DatabaseMetaData.importedKeyNoAction -> "NO ACTION";
            default -> "UNKNOWN(" + r + ")";
        };
    }

    private static String sqlTypeName(int type) {
        try { return JDBCType.valueOf(type).getName(); }
        catch (IllegalArgumentException e) { return "UNKNOWN(" + type + ")"; }
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private List<DbMetadataUtils.PrivilegeGrant> queryGrants(String sql, String roleName) {
        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new DbMetadataUtils.PrivilegeGrant(
                        "DATABASE".equals(rs.getString("object_type"))
                                ? "DATABASE"
                                : rs.getString("object_type") != null
                                ? rs.getString("object_type")
                                : "SCHEMA",
                        rs.getString("object_name"),
                        rs.getString("owner"),
                        rs.getString("grantee"),
                        rs.getString("privilege"),
                        rs.getBoolean("grantable"),
                        rs.getString("grantor")
                ),
                roleName
        );
    }
}
