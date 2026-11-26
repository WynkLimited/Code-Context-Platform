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
            assertEquals(13, result.migrationsExecuted, "runtime role must execute V1-V13");

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
                    assertEquals(13, history.getInt(1));
                }
            }
            assertSharedBudget(url, role, password, schema);
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
    private void assertSharedBudget(String url, String role, String password, String schema) throws Exception {
        String scopedUrl = url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema + ",public";
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(scopedUrl, role, password));
        var first = com.wynk.codecontext.security.RateLimitGate.postgres(12, jdbc);
        var second = com.wynk.codecontext.security.RateLimitGate.postgres(12,
                new org.springframework.jdbc.core.JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(scopedUrl, role, password)));
        // Keep this contention fixture away from the wall-clock minute boundary without sleeping or mocking PostgreSQL.
        jdbc.update("INSERT INTO api_rate_limit_windows(team,window_start,used) VALUES ('orders', date_trunc('minute', statement_timestamp()) + interval '1 minute', 1)");
        try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var calls = new java.util.ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 32; i++) {
                var gate = i % 2 == 0 ? first : second;
                calls.add(workers.submit(() -> gate.check("orders").allowed()));
            }
            int allowed = 0;
            for (var call : calls) if (call.get(15, java.util.concurrent.TimeUnit.SECONDS)) allowed++;
            assertEquals(11, allowed, "two independent gate instances must share the remaining eleven requests atomically");
        }
        assertEquals(33L, jdbc.queryForObject("SELECT used FROM api_rate_limit_windows WHERE team='orders'", Long.class));
        assertFalse(com.wynk.codecontext.security.RateLimitGate.postgres(1, jdbc).check("orders").allowed());
        assertEquals(34L, jdbc.queryForObject("SELECT used FROM api_rate_limit_windows WHERE team='orders'", Long.class),
                "a lower-budget replica during rolling configuration must never rewind the shared count");
        assertTrue(second.check("payments").allowed(), "teams have independent budgets");
        assertFalse(com.wynk.codecontext.security.RateLimitGate.postgres(12, jdbc).check("orders").allowed(), "restart must retain the exhausted window");
        jdbc.update("UPDATE api_rate_limit_windows SET used=2147483648 WHERE team='orders'");
        assertFalse(first.check("orders").allowed());
        assertEquals(2147483648L, jdbc.queryForObject("SELECT used FROM api_rate_limit_windows WHERE team='orders'", Long.class));
        jdbc.update("UPDATE api_rate_limit_windows SET window_start = statement_timestamp() - interval '2 minutes' WHERE team='orders'");
        var reset = first.check("orders");
        assertTrue(reset.allowed());
        assertEquals(11, reset.remaining());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM api_rate_limit_windows", Integer.class), "one row per team, not per request/window");
    }

}
