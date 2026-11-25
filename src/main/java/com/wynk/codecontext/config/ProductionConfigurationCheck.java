package com.wynk.codecontext.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class ProductionConfigurationCheck {
    @Bean ApplicationRunner productionSettings(Environment env,CodeContextProperties p) {
        return args -> {
            if(java.util.Arrays.asList(env.getActiveProfiles()).contains("production")) {
                if(p.webhookSecret()==null||p.webhookSecret().length()<32||p.webhookSecret().contains("change-me"))throw new IllegalStateException("production WEBHOOK_SECRET must contain at least 32 non-default characters");
                if(p.gitAccessToken()==null||p.gitAccessToken().isBlank())throw new IllegalStateException("GIT_ACCESS_TOKEN is required in production");
                com.wynk.codecontext.security.ApiKeyRoster.requireProductionStrength(p.apiKeys(),"TEAM_API_KEYS");
                com.wynk.codecontext.security.ApiKeyRoster.requireProductionStrength(p.adminApiKeys(),"ADMIN_API_KEYS");
                if(p.mcpAllowedOrigins()==null||p.mcpAllowedOrigins().contains("*")||p.mcpAllowedOrigins().isBlank())throw new IllegalStateException("MCP_ALLOWED_ORIGINS must be an explicit exact allowlist");
                for(String origin:p.mcpAllowedOrigins().split(",")){String value=origin.strip();if(!value.startsWith("https://")&&!value.matches("http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?"))throw new IllegalStateException("production MCP origins must use HTTPS (except loopback)");}
                if(env.getProperty("spring.datasource.password")==null||env.getProperty("spring.datasource.password").contains("change_me"))throw new IllegalStateException("DATABASE_PASSWORD must be set in production");
            }
        };
    }

    @Bean FlywayMigrationStrategy databaseRoleGuard(Environment env) {
        return flyway -> {
            if(java.util.Arrays.asList(env.getActiveProfiles()).contains("production"))requireNonSuperuser(new JdbcTemplate(flyway.getConfiguration().getDataSource()));
            flyway.migrate();
        };
    }

    static void requireNonSuperuser(JdbcTemplate jdbc) {
        Boolean superuser=jdbc.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user",Boolean.class);
        if(Boolean.TRUE.equals(superuser))throw new IllegalStateException("production DATABASE_USER must not be a PostgreSQL superuser");
    }
}
