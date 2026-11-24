package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class ApiKeyFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);
    private final Map<String, String> teamKeys;
    private final Map<String,String> adminKeys;
    public ApiKeyFilter(CodeContextProperties properties) {
        this.teamKeys = Arrays.stream(properties.apiKeys().split(","))
                .map(String::strip).filter(v -> v.contains("="))
                .map(v -> v.split("=", 2)).collect(Collectors.toUnmodifiableMap(a -> a[1], a -> a[0], (a,b)->a));
        this.adminKeys=Arrays.stream(properties.adminApiKeys().split(","))
                .map(String::strip).filter(v -> v.contains("="))
                .map(v -> v.split("=",2)).collect(Collectors.toUnmodifiableMap(a->a[1],a->a[0],(a,b)->a));
    }
    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        String key = req.getHeader("X-API-Key");String team=null;
        String adminTeam=constantLookup(key,adminKeys);
        if(adminTeam!=null)team=adminTeam;
        else team=constantLookup(key,teamKeys);
        if (team != null) {var roles=new java.util.ArrayList<SimpleGrantedAuthority>();roles.add(new SimpleGrantedAuthority("ROLE_TEAM"));if(adminTeam!=null)roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(team, "", roles));}
        log.debug("MCP/security dispatch before chain: dispatcher={}, method={}, uri={}, apiKeyPresent={}, authenticated={}, principalType={}, anonymous={}, mcpSessionPresent={}, responseCommitted={}",
                req.getDispatcherType(), req.getMethod(), req.getRequestURI(), key != null, isAuthenticated(), principalType(),
                isAnonymous(), req.getHeader("Mcp-Session-Id") != null, res.isCommitted());
        try {
            chain.doFilter(req, res);
        } finally {
            log.debug("MCP/security dispatch after chain: dispatcher={}, method={}, uri={}, apiKeyPresent={}, authenticated={}, principalType={}, anonymous={}, mcpSessionPresent={}, responseCommitted={}",
                    req.getDispatcherType(), req.getMethod(), req.getRequestURI(), key != null, isAuthenticated(), principalType(),
                    isAnonymous(), req.getHeader("Mcp-Session-Id") != null, res.isCommitted());
        }
    }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    private static boolean isAuthenticated() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }
    private static String principalType() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getPrincipal() == null
                ? "none" : authentication.getPrincipal().getClass().getName();
    }
    private static boolean isAnonymous() {
        return SecurityContextHolder.getContext().getAuthentication() instanceof AnonymousAuthenticationToken;
    }
    private static String constantLookup(String key,Map<String,String> keys){if(key==null)return null;String found=null;byte[] supplied=key.getBytes(java.nio.charset.StandardCharsets.UTF_8);for(var entry:keys.entrySet())if(java.security.MessageDigest.isEqual(supplied,entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)))found=entry.getValue();return found;}
}
