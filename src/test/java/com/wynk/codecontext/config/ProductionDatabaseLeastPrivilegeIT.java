package com.wynk.codecontext.config;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies all migrations can be installed by a database-scoped, non-superuser runtime role. */
class ProductionDatabaseLeastPrivilegeIT {
    @Test
    void allMigrationsRunAsNonSuperuserAfterExtensionsAreProvisioned() throws Exception {
        String url = System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/code_context_test");
        String admin = System.getenv().getOrDefault("DATABASE_USER", "code_context");
        String adminPassword = System.getenv().getOrDefault("DATABASE_PASSWORD", "integration_test_password");
        String role = "ccp_runtime_" + UUID.randomUUID().toString().replace("-", "");
        String schema = "ccp_schema_" + UUID.randomUUID().toString().replace("-", "");
        String password = UUID.randomUUID().toString() + UUID.randomUUID();

        try (var adminConnection = DriverManager.getConnection(url, admin, adminPassword);
             var statement = adminConnection.createStatement()) {
            String database = "";
            try (var result = statement.executeQuery("SELECT current_database()")) {
                if (result.next()) database = result.getString(1);
            }
            if (!database.endsWith("_test")) {
                throw new IllegalStateException("Refusing to create test roles outside a *_test database: " + database);
            }
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector");
            statement.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
            statement.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '" + password + "'");
            statement.execute("GRANT CONNECT ON DATABASE " + database + " TO " + role);
            statement.execute("CREATE SCHEMA " + schema + " AUTHORIZATION " + role);

            var flyway = Flyway.configure()
                    .dataSource(url, role, password)
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .placeholders(Map.of("vectorDimensions", "1024"))
                    .load();
            var result = flyway.migrate();
            assertEquals(10, result.migrationsExecuted, "runtime role must execute V1-V10");

            try (var runtimeConnection = DriverManager.getConnection(url, role, password);
                 var runtimeStatement = runtimeConnection.createStatement()) {
                try (var flags = runtimeStatement.executeQuery("SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user")) {
                    assertTrue(flags.next());
                    assertFalse(flags.getBoolean("rolsuper"));
                    assertFalse(flags.getBoolean("rolcreatedb"));
                    assertFalse(flags.getBoolean("rolcreaterole"));
                }
                try (var history = runtimeStatement.executeQuery("SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success")) {
                    assertTrue(history.next());
                    assertEquals(10, history.getInt(1));
                }
            }
        } finally {
            try (var cleanup = DriverManager.getConnection(url, admin, adminPassword);
                 var statement = cleanup.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                statement.execute("DROP OWNED BY " + role);
                statement.execute("DROP ROLE IF EXISTS " + role);
            } catch (SQLException cleanupError) {
                throw new IllegalStateException("Could not clean up temporary runtime-role integration fixture", cleanupError);
            }
        }
    }
}
