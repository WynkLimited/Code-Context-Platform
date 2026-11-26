package com.wynk.codecontext.repo;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/repositories")
public final class RepositoryConfigurationController {
    private final RepositoryConfigurationDiscovery discovery;
    public RepositoryConfigurationController(RepositoryConfigurationDiscovery discovery){this.discovery=discovery;}
    @GetMapping("/{owner}/{name}/configuration")
    public Map<String,Object> configuration(@PathVariable String owner,@PathVariable String name,Authentication auth) {
        return discovery.discover(owner,name,String.valueOf(auth.getPrincipal()));
    }
}
