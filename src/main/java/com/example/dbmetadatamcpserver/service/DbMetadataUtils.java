package com.example.dbmetadatamcpserver.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;

@RequiredArgsConstructor
@Service
public class DbMetadataUtils {

    private final JdbcTemplate jdbcTemplate;

    @FunctionalInterface
    public interface MetaFn<T> { T apply(DatabaseMetaData databaseMetaData) throws SQLException; }

    public <T> T meta(MetaFn<T> fn) {
        return jdbcTemplate.execute((ConnectionCallback<T>) con -> fn.apply(con.getMetaData()));
    }

    public record DatabaseInfo(String product, String version, String driver, String driverVersion) {}

    public record TableInfo(String schema, String name, String type, String remarks) {}

    public record ViewInfo(String catalog, String schema, String name, String remarks) { }

    public record ColumnInfo(String name, String type, int size, boolean nullable,
                             String defaultValue, boolean primaryKey, String remarks) {}

    public record IndexInfo(String indexName, String columnName, boolean unique, int position) {}

    public record ForeignKey(String name, String fkTable, String fkColumn,
                             String pkTable, String pkColumn, String direction) {}

    public record ColumnMatch(String schema, String table, String column, String type) {}

    public record RoleDetails(
            DatabaseRole role,
            List<RoleMembership> memberships,
            List<RoleMembership> grantedMemberships,
            List<PrivilegeGrant> databaseGrants,
            List<PrivilegeGrant> schemaGrants,
            List<PrivilegeGrant> objectGrants
    ) {}

    public record DatabaseRole(
            String name,
            boolean superuser,
            boolean inherit,
            boolean createRole,
            boolean createDatabase,
            boolean canLogin,
            boolean replication,
            boolean bypassRls,
            int connectionLimit,
            String validUntil
    ) {}

    public record RoleMembership(
            String member,
            String grantedRole,
            String grantor,
            boolean adminOption
    ) {}

    public record PrivilegeGrant(
            String scope,
            String objectName,
            String owner,
            String grantee,
            String privilege,
            boolean grantable,
            String grantor
    ) {}

    public record RolesAndGrants(
            String database,
            List<DatabaseRole> roles,
            List<RoleMembership> memberships,
            List<PrivilegeGrant> databaseGrants,
            List<PrivilegeGrant> schemaGrants,
            List<PrivilegeGrant> objectGrants
    ) {}

    public record EntityTableInfo(
            String database, String databaseVersion,
            String schema, String table, String tableType, String remarks,
            List<EntityColumn> columns,
            List<String> primaryKeyColumns,   // in key order; empty if none
            String primaryKeyName,
            List<EntityForeignKey> outgoingForeignKeys,
            List<EntityForeignKey> incomingForeignKeys,
            List<EntityIndex> indexes,
            List<String> warnings) {}

    public record EntityColumn(
            int position, String name, String sqlTypeName, String dbTypeName,
            int size, Integer scale, boolean nullable, String defaultValue,
            boolean autoIncrement, boolean generated, String remarks) {}

    public record EntityForeignKey(
            String name, String fkSchema, String fkTable, List<String> fkColumns,
            String pkSchema, String pkTable, List<String> pkColumns,
            String onUpdate, String onDelete) {}

    public record EntityIndex(String name, boolean unique, List<String> columns) {}

    public record JoinStep(String fromTable, String toTable, String constraint, String joinCondition) {}

}
