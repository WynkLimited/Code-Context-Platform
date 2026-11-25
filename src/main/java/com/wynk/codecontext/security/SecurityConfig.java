package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration @EnableWebSecurity
public class SecurityConfig {
    @Bean ApiKeyFilter apiKeyFilter(CodeContextProperties p) { return new ApiKeyFilter(p); }
    @Bean McpOriginFilter mcpOriginFilter(CodeContextProperties p){return new McpOriginFilter(p);}
    @Bean RateLimitGate rateLimitGate(@Value("${code-context.rate-limit-per-minute:0}") int limitPerMinute) {
        return RateLimitGate.perMinute(limitPerMinute);
    }
    @Bean RateLimitFilter rateLimitFilter(RateLimitGate gate) { return new RateLimitFilter(gate); }
    @Bean FilterRegistrationBean<ApiKeyFilter> apiKeyFilterServletRegistration(ApiKeyFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean FilterRegistrationBean<McpOriginFilter> mcpOriginFilterServletRegistration(McpOriginFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean FilterRegistrationBean<RateLimitFilter> rateLimitFilterServletRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean SecurityFilterChain security(HttpSecurity http, ApiKeyFilter filter,McpOriginFilter origin,RateLimitFilter rateLimit) throws Exception {
        return http.csrf(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health/**", "/webhooks/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(origin,UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(rateLimit, McpOriginFilter.class).build();
    }
}
