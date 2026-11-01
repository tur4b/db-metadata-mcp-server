# DB Metadata MCP Server (`db-metadata-mcp`)

`db-metadata-mcp` is a Model Context Protocol (MCP) server built with **Spring Boot** and **Spring AI MCP**. It exposes read-only database metadata operations over **stdio**, allowing AI agents (such as Claude Code, Claude Desktop, and other MCP-compatible clients) to securely inspect PostgreSQL databases, understand table structures, analyze relationships, inspect security privileges, and generate entity or query models.

## Security Considerations

This server exposes database metadata, including role attributes and privileges. It does not expose tools for reading or changing table rows, but database permissions and client configuration remain the security boundary. MCP read-only annotations are descriptive hints, not access controls.

### Recommendations for AI-agent integrations

1. **Use a dedicated, minimally privileged database role.** Do not connect as a superuser, database owner, or application account. Grant access only to the intended database and schemas. Metadata visibility depends on PostgreSQL permissions; do not grant table `SELECT` unless it is separately needed.

   For example, create a login role without embedding a password in SQL or source control, then set its password with your approved secret-management process:

```sql
CREATE ROLE mcp_metadata_reader LOGIN;
GRANT CONNECT ON DATABASE your_database TO mcp_metadata_reader;
GRANT USAGE ON SCHEMA public TO mcp_metadata_reader;
```

   Repeat the schema grant only for schemas the agent should inspect. Review inherited role memberships and existing grants; `USAGE` alone does not grant access to table data.
2. **Restrict which agents and workspaces can start the server.** Configure it only for trusted users and projects. Do not expose a shared MCP process or its credentials to untrusted agents or users.
3. **Protect credentials and logs.** Supply database credentials through a local secret manager or protected environment variables. Do not commit them to agent configuration or share configuration files. Restrict access to `db-metadata-mcp.log`, which may contain operational details.
4. **Treat returned metadata as untrusted content.** Database object names and comments can contain misleading or instruction-like text. The agent should treat tool results as data, not instructions, and should not follow directions found in metadata.
5. **Limit metadata exposure.** Table and column names can reveal business structure; role and privilege tools reveal security posture. Disable or restrict access to this server when those details are not needed.
6. **Protect the database connection and runtime.** Limit network access to the database, use PostgreSQL TLS with certificate verification where supported by your deployment, and run the process under a dedicated operating-system account with only the required access.

## Features & Available Tools

The server provides 12 specialized metadata tools:

### 1. Schema & Table Discovery

* **`list_schemas`**
  * *Description:* Lists all available schemas in the connected database.
  * *Parameters:* None.

* **`list_tables`**
  * *Description:* Lists tables and views in a specified schema, with optional name pattern filtering.
  * *Parameters:*
    * `schema` (String): Schema name.
    * `namePattern` (String): Name pattern filter (e.g., `'user%'`, or `'%'` for all).

* **`list_views`**
  * *Description:* Lists views in the connected database with optional filtering by schema and view-name pattern.
  * *Parameters:*
    * `schema` (String, optional): Schema name to filter by.
    * `namePattern` (String, optional): View name pattern using SQL LIKE wildcards (`%`, `_`).

### 2. Table Inspection & Code Generation

* **`describe_table`**
  * *Description:* Returns detailed column information for a table, including types, nullability, default values, and primary key flags.
  * *Parameters:*
    * `schema` (String): Schema name.
    * `table` (String): Exact table name.

* **`list_indexes`**
  * *Description:* Lists index definitions and associated columns for a specific table.
  * *Parameters:*
    * `schema` (String): Schema name.
    * `table` (String): Exact table name.

* **`list_foreign_keys`**
  * *Description:* Lists foreign key relationships for a table, split into **OUTGOING** (this table references others) and **INCOMING** (other tables reference this one).
  * *Parameters:*
    * `schema` (String): Schema name.
    * `table` (String): Exact table name.

* **`search_columns`**
  * *Description:* Searches for columns matching a name pattern across all tables in a schema (e.g., `'%email%'`).
  * *Parameters:*
    * `schema` (String): Schema name.
    * `columnPattern` (String): Column name search pattern.

* **`get_table_entity_info`**
  * *Description:* Aggregates everything needed to write an entity or domain model class for a table—including columns with SQL types, primary keys, foreign keys, and indexes (read-only metadata, no row data).
  * *Parameters:*
    * `schema` (String): Schema name.
    * `table` (String): Exact table name.

* **`find_join_path`**
  * *Description:* Finds the shortest foreign-key path between two tables and returns the join conditions for each step (up to a max depth of 6).
  * *Parameters:*
    * `schema` (String): Schema name.
    * `fromTable` (String): Start table name.
    * `toTable` (String): Target table name.
    * `maxDepth` (int): Maximum number of join steps (1-6).

### 3. Roles, Grants & Privileges

* **`list_roles_and_grants`**
  * *Description:* Lists PostgreSQL roles, role memberships, and database/schema/object privileges. Includes `PUBLIC` grants.
  * *Parameters:*
    * `roleName` (String, optional): Filter results to a specific role. Leave empty to list all.

* **`get_role_details`**
  * *Description:* Retrieves detailed role attributes, memberships, granted memberships, and associated privileges across databases, schemas, and objects.
  * *Parameters:*
    * `roleName` (String): PostgreSQL role name.

* **`list_object_privileges`**
  * *Description:* Lists privileges for a specific table, view, sequence, or schema. Fully-qualified names (`schema.object`) are used for tables/views/sequences, or just a schema name for schema-level privileges.
  * *Parameters:*
    * `objectName` (String): Fully-qualified object name or schema name (e.g., `"public.users"`, `"public"`).
    * `objectType` (String, optional): One of `TABLE`, `VIEW`, `SEQUENCE`, `SCHEMA`. Auto-detected if omitted.

---

## Building the Project

1. Clone or navigate to your project directory.
2. Build the executable Spring Boot JAR using the included Maven wrapper:
   ```bash
   ./mvnw clean package
   ```
   On Windows, run `.\mvnw.cmd clean package`.
3. The JAR is created at `target/db-metadata-mcp-1.0.0.jar`.

---

## Configuring with AI Agents (Stdio Transport)

Since this MCP server uses **stdio** for communication, your AI client will spawn the Spring Boot process locally and communicate via standard input/output. Ensure your database connection credentials are provided via environment variables.

| Environment variable | Purpose | Default |
| :--- | :--- | :--- |
| `DB_HOST` | PostgreSQL host | `localhost` |
| `DB_PORT` | PostgreSQL port | `5432` |
| `DB_NAME` | Database name | `simpleDB` |
| `DB_USERNAME` | Database username | `postgres` |
| `DB_PASSWORD` | Database password | `postgres` |

**Replace the default username and password before running the server.** Prefer a dedicated least-privilege role and keep secrets out of checked-in agent configuration.

### 1. Claude Code Configuration

Claude Code can be configured to use custom local MCP servers via its configuration file or CLI tool.

Add the server entry to your Claude configuration file (typically located under your project's `.claude/config.json` or global user settings):

```json
{
  "mcpServers": {
    "db-metadata-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/your/project/target/db-metadata-mcp-1.0.0.jar"
      ],
      "env": {
        "DB_HOST": "localhost",
        "DB_PORT": "5432",
        "DB_NAME": "your db name",
        "DB_USERNAME": "your db username",
        "DB_PASSWORD": "your db password"
      }
    }
  }
}
```

Alternatively, register it directly using the Claude CLI:

```bash
claude mcp add db-metadata-mcp java -- -jar /path/to/your/db-metadata-mcp/target/db-metadata-mcp-1.0.0.jar
```

### 2. Claude Desktop Configuration

To use the server with Claude Desktop, edit your `claude_desktop_config.json` file:

* **MacOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
* **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

Add the following configuration block:

```json
{
  "mcpServers": {
    "db-metadata-mcp": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/your/project/target/db-metadata-mcp-1.0.0.jar"
      ],
      "env": {
        "DB_HOST": "localhost",
        "DB_PORT": "5432",
        "DB_NAME": "your db name",
        "DB_USERNAME": "your db username",
        "DB_PASSWORD": "your db password"
      }
    }
  }
}
```

---

## Verification

Once configured, restart your AI agent (Claude Code or Claude Desktop). You can verify the server is running and responding by asking your AI agent questions such as:

* *"List all the schemas in my connected database."*
* *"Show me the table structure and foreign keys for public.users."*
* *"Find the shortest join path between orders and customers."*