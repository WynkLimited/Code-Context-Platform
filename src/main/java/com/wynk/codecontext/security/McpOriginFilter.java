package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Arrays;

public class McpOriginFilter extends OncePerRequestFilter {
    private final Set<String> allowed;
    public McpOriginFilter(CodeContextProperties p){allowed=Arrays.stream(p.mcpAllowedOrigins().split(",")).map(String::strip).filter(s->!s.isEmpty()).collect(Collectors.toUnmodifiableSet());}
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain)throws ServletException,IOException {
        if(req.getRequestURI().equals("/mcp")){String origin=req.getHeader("Origin");if(origin!=null&&!allowed.contains(origin)){res.sendError(HttpServletResponse.SC_FORBIDDEN,"origin is not allowed");return;}}
        chain.doFilter(req,res);
    }
}
