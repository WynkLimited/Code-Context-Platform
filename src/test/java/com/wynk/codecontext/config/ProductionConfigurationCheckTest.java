package com.wynk.codecontext.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionConfigurationCheckTest {
    @Test
    void rejectsPostgresSuperuserInProduction() {
        JdbcTemplate jdbc = new RoleJdbcTemplate(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProductionConfigurationCheck.requireNonSuperuser(jdbc));

        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("must not be a PostgreSQL superuser"));
    }

    @Test
    void acceptsNonSuperuserDatabaseRoleInProduction() {
        JdbcTemplate jdbc = new RoleJdbcTemplate(false);

        assertDoesNotThrow(() -> ProductionConfigurationCheck.requireNonSuperuser(jdbc));
    }

    private static final class RoleJdbcTemplate extends JdbcTemplate {
        private final boolean superuser;

        private RoleJdbcTemplate(boolean superuser) {
            this.superuser = superuser;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            if (!"SELECT rolsuper FROM pg_roles WHERE rolname = current_user".equals(sql)
                    || !Boolean.class.equals(requiredType)) {
                throw new AssertionError("Unexpected database probe: " + sql);
            }
            return requiredType.cast(superuser);
        }
    }
}
