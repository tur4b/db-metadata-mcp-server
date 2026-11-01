package com.example.dbmetadatamcpserver.ai;

import com.example.dbmetadatamcpserver.service.DbMetadataOperations;
import com.example.dbmetadatamcpserver.service.DbMetadataUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class DbMetadataTools {

    private final DbMetadataOperations dbMetadataOperations;

    @McpTool(name = "list_schemas",
            description = "List schemas in the database",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<String> listSchemas() {
        return dbMetadataOperations.listSchemas();
    }

    @McpTool(
            name = "list_roles_and_grants",
            description = """
                Lists PostgreSQL roles, role memberships, and database,
                schema, and object privileges for the current database.

                An optional roleName filters the result to a particular role.
                PUBLIC grants are also included.

                This is a read-only metadata operation.
                """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public DbMetadataUtils.RolesAndGrants listRolesAndGrants(
            @McpToolParam(
                    description = "Optional PostgreSQL role name. Leave empty to list all roles.",
                    required = false
            )
            String roleName
    ) {
        return dbMetadataOperations.listRolesAndGrants(roleName);
    }

    @McpTool(name = "get_role_details",
            description = """
            Get detailed information about a PostgreSQL database role.
            Returns role attributes, role memberships, granted memberships,
            database privileges, schema privileges, and table/view/sequence
            privileges.
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public DbMetadataUtils.RoleDetails getRoleDetails(
            @McpToolParam(description = "PostgreSQL role name") String roleName) {
        return dbMetadataOperations.getRoleDetails(roleName);
    }

    @McpTool(
            name = "list_object_privileges",
            description = """
        Lists privileges for a specific table, view, sequence, or schema
        in the current PostgreSQL database.

        Pass a fully-qualified name (schema.object) for tables, views, and
        sequences — e.g. "public.users" or "public.order_seq".
        Pass just a schema name — e.g. "public" — for schema-level privileges.

        The objectType parameter is optional: when omitted the tool
        auto-detects SCHEMA (no dot) vs TABLE (dot present).

        Returns each ACE (Access Control Entry) expanded into its own row,
        including PUBLIC grants.

        This is a read-only metadata operation.
        """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.PrivilegeGrant> listObjectPrivileges(
            @McpToolParam(
                    description = "Fully-qualified table/view/sequence name (schema.object) " +
                            "or a schema name. Examples: \"public.users\", \"public\"."
            )
            String objectName,

            @McpToolParam(
                    description = "Optional. One of: TABLE, VIEW, SEQUENCE, SCHEMA. " +
                            "When omitted the tool auto-detects based on the objectName.",
                    required = false
            )
            String objectType
    ) {
        return dbMetadataOperations.listObjectPrivileges(objectName, objectType);
    }

    @McpTool(name = "list_tables",
            description = "List tables and views in a schema, optionally filtered by name pattern (% is a wildcard)",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.TableInfo> listTables(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Name pattern, e.g. 'user%'. Use '%' for all") String namePattern) {
        return dbMetadataOperations.listTables(schema, namePattern);
    }

    @McpTool(name = "list_views",
            description = "Lists the views in the connected database. "
                    + "Optionally filter by schema and/or by view-name pattern "
                    + "(SQL LIKE wildcards: % matches any string, _ matches one character).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.ViewInfo> listViews(
            @McpToolParam(description = "Schema name to filter by. Omit to include all schemas.",
                    required = false) String schema,
            @McpToolParam(description = "View name pattern using % and _ wildcards. Omit to list all views.",
                    required = false) String namePattern) {

        // The cast picks the ConnectionCallback overload of JdbcTemplate.execute(...)
        return dbMetadataOperations.listViews(schema, namePattern);
    }

    @McpTool(name = "describe_table",
            description = "Columns of a table with types, nullability, defaults and primary key flag",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.ColumnInfo> describeTable(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Exact table name") String table) {
        return dbMetadataOperations.describeTable(schema, table);
    }

    @McpTool(name = "list_indexes",
            description = "Indexes of a table with their columns",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.IndexInfo> listIndexes(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Exact table name") String table) {
        return dbMetadataOperations.listIndexes(schema, table);
    }

    @McpTool(name = "list_foreign_keys",
            description = "Foreign keys of a table: OUTGOING (this table references others) and INCOMING (others reference it)",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.ForeignKey> listForeignKeys(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Exact table name") String table) {
        return dbMetadataOperations.listForeignKeys(schema, table);
    }

    @McpTool(name = "search_columns",
            description = "Find columns by name pattern across all tables in a schema, e.g. '%email%'",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.ColumnMatch> searchColumns(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Column name pattern") String columnPattern) {
        return dbMetadataOperations.searchColumns(schema, columnPattern);
    }

    @McpTool(name = "get_table_entity_info",
            description = "Get everything needed to write an entity/model class for a table: "
                    + "columns with SQL types, primary key, outgoing and incoming foreign keys, "
                    + "and indexes. Read-only metadata, no row data.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public DbMetadataUtils.EntityTableInfo getTableEntityInfo(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Exact table name") String table) {
        return dbMetadataOperations.getTableEntityInfo(schema, table);
    }

    @McpTool(name = "find_join_path",
            description = "Find the shortest foreign-key path between two tables and the join "
                    + "conditions for each step. Empty result means no path within maxDepth.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true)
    )
    public List<DbMetadataUtils.JoinStep> findJoinPath(
            @McpToolParam(description = "Schema name") String schema,
            @McpToolParam(description = "Start table") String fromTable,
            @McpToolParam(description = "Target table") String toTable,
            @McpToolParam(description = "Maximum number of joins (1-6)") int maxDepth) {

        int depthLimit = Math.min(Math.max(maxDepth, 1), 6);
        if (fromTable.equalsIgnoreCase(toTable)) return List.of();
        return dbMetadataOperations.findJoinPath(schema, fromTable, toTable, depthLimit);
    }
}
