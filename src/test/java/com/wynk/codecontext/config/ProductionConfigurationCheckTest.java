package com.wynk.codecontext.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionConfigurationCheckTest {
    @Test void productionRequiresAReadOnlyGitCredentialOrCompleteAppConfiguration() throws Exception {
        var environment=new org.springframework.mock.env.MockEnvironment().withProperty("spring.datasource.password","operator-managed-password");
        environment.setActiveProfiles("production");
        var properties=new CodeContextProperties("/repos","/fixtures","/zoekt","team="+"t".repeat(40),"team="+"a".repeat(40),
                "h".repeat(40),"","github.com","https://context.example",1024,1048576,6000,2,30,
                new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("graphify",false));
        var guard=new ProductionConfigurationCheck();
        assertThrows(IllegalStateException.class,()->guard.productionSettings(environment,properties).run(new org.springframework.boot.DefaultApplicationArguments(new String[0])));
        environment.withProperty("GITHUB_APP_CLIENT_ID","fixture-client-id");
        assertThrows(IllegalStateException.class,()->guard.productionSettings(environment,properties).run(new org.springframework.boot.DefaultApplicationArguments(new String[0])));
        environment.withProperty("GITHUB_APP_PRIVATE_KEY_FILE","/run/secrets/operator-app-key.pem");
        assertDoesNotThrow(()->guard.productionSettings(environment,properties).run(new org.springframework.boot.DefaultApplicationArguments(new String[0])));
    }
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
