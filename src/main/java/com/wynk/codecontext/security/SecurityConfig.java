package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration @EnableWebSecurity
public class SecurityConfig {
    @Bean ApiKeyFilter apiKeyFilter(CodeContextProperties p) { return new ApiKeyFilter(p); }
    @Bean McpOriginFilter mcpOriginFilter(CodeContextProperties p){return new McpOriginFilter(p);}
    @Bean SecurityFilterChain security(HttpSecurity http, ApiKeyFilter filter,McpOriginFilter origin) throws Exception {
        return http.csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/webhooks/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(origin,UsernamePasswordAuthenticationFilter.class).build();
    }
}
