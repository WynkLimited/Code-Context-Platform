package com.wynk.codecontext.config;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ProductionConfigurationCheck {
    @Bean ApplicationRunner productionSettings(Environment env,CodeContextProperties p) {
        return args -> {
            if(java.util.Arrays.asList(env.getActiveProfiles()).contains("production")) {
                if(p.webhookSecret()==null||p.webhookSecret().length()<32||p.webhookSecret().contains("change-me"))throw new IllegalStateException("production WEBHOOK_SECRET must contain at least 32 non-default characters");
                if(p.gitAccessToken()==null||p.gitAccessToken().isBlank())throw new IllegalStateException("GIT_ACCESS_TOKEN is required in production");
                if(p.apiKeys()==null||p.apiKeys().isBlank()||p.apiKeys().contains("local-dev-token"))throw new IllegalStateException("TEAM_API_KEYS must configure production team tokens");
                for(String entry:p.apiKeys().split(",")){String[] pair=entry.strip().split("=",2);if(pair.length!=2||pair[1].length()<32)throw new IllegalStateException("each production team key must contain at least 32 characters");}
                if(p.adminApiKeys()==null||p.adminApiKeys().isBlank()||p.adminApiKeys().contains("local-dev-token"))throw new IllegalStateException("ADMIN_API_KEYS must configure production administrator tokens");
                for(String entry:p.adminApiKeys().split(",")){String[] pair=entry.strip().split("=",2);if(pair.length!=2||pair[1].length()<32)throw new IllegalStateException("each production administrator key must contain at least 32 characters");}
                if(p.mcpAllowedOrigins()==null||p.mcpAllowedOrigins().contains("*")||p.mcpAllowedOrigins().isBlank())throw new IllegalStateException("MCP_ALLOWED_ORIGINS must be an explicit exact allowlist");
                for(String origin:p.mcpAllowedOrigins().split(",")){String value=origin.strip();if(!value.startsWith("https://")&&!value.matches("http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?"))throw new IllegalStateException("production MCP origins must use HTTPS (except loopback)");}
                if(env.getProperty("spring.datasource.password")==null||env.getProperty("spring.datasource.password").contains("change_me"))throw new IllegalStateException("DATABASE_PASSWORD must be set in production");
            }
        };
    }
}
