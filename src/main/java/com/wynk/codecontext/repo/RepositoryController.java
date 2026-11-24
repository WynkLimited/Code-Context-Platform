package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/api/repositories")
public class RepositoryController {
    private final RepositoryStore store; private final CodeContextProperties props; private final JdbcTemplate jdbc;
    public RepositoryController(RepositoryStore s, CodeContextProperties p, JdbcTemplate j) {store=s;props=p;jdbc=j;}
    @PostMapping public RepositoryRecord onboard(@Valid @RequestBody OnboardRequest request, Authentication auth) {
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))throw new org.springframework.security.access.AccessDeniedException("repository onboarding requires an administrator key");
        if(!request.owner().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")||!request.name().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")||request.owner().equals("..")||request.name().equals(".."))throw new IllegalArgumentException("owner and name must be safe repository components");
        if(request.type()!=null&&!java.util.Set.of("SERVICE","LIBRARY").contains(request.type().toUpperCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("repository type must be SERVICE or LIBRARY");
        String team=(String)auth.getPrincipal();
        Path mirror=Path.of(props.repositoryRoot()).toAbsolutePath().normalize().resolve(request.owner()).resolve(request.name());
        RepositoryRecord repo=store.insert(request.owner(),request.name(),request.cloneUrl(),team,request.defaultBranch()==null?"main":request.defaultBranch(),request.type()==null?"SERVICE":request.type(),mirror.toString());
        jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES(?,?) ON CONFLICT DO NOTHING",team,repo.id());
        return repo;
    }
    @GetMapping("/{owner}/{name}") public RepositoryRecord get(@PathVariable String owner,@PathVariable String name,Authentication auth) {
        return store.find(owner,name).filter(r->jdbc.queryForObject("SELECT count(*) FROM team_repository_access WHERE team=? AND repository_id=?",Integer.class,auth.getPrincipal(),r.id())>0).orElseThrow();
    }
    @DeleteMapping("/{owner}/{name}") @Transactional public Map<String,Object> disable(@PathVariable String owner,@PathVariable String name,Authentication auth) {
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))throw new org.springframework.security.access.AccessDeniedException("repository removal requires an administrator key");
        RepositoryRecord repo=store.find(owner,name).orElseThrow();
        store.disable(repo.id());
        return Map.of("repository",repo.key(),"status","DISABLED","historyPreserved",true);
    }
    public record OnboardRequest(@NotBlank String owner,@NotBlank String name,@NotBlank String cloneUrl,String defaultBranch,String type) {}
}
