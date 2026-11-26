package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.index.GenerationRetentionService;
import com.wynk.codecontext.index.RepositoryIndexLeaseManager;
import com.wynk.codecontext.model.RepositoryRecord;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

@RestController @RequestMapping("/api/repositories")
public class RepositoryController {
    private final RepositoryStore store; private final CodeContextProperties props; private final JdbcTemplate jdbc;
    private final GitRepositoryManager git; private final RepositoryIndexLeaseManager leases;
    private final GenerationRetentionService retention; private final TransactionTemplate transactions;
    private final RepositoryOnboarding onboarding;
    private final RepositoryLifecycle lifecycle;
    @org.springframework.beans.factory.annotation.Autowired
    public RepositoryController(RepositoryStore s, CodeContextProperties p, JdbcTemplate j, GitRepositoryManager git,
                                RepositoryIndexLeaseManager leases, GenerationRetentionService retention,
                                PlatformTransactionManager tx, RepositoryOnboarding onboarding,RepositoryLifecycle lifecycle) {
        store=s;props=p;jdbc=j;this.git=git;this.leases=leases;this.retention=retention;
        this.transactions=new TransactionTemplate(tx);
        this.onboarding=onboarding;
        this.lifecycle=lifecycle;
    }
    @PostMapping public RepositoryRecord onboard(@Valid @RequestBody OnboardRequest request, Authentication auth) {
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))throw new org.springframework.security.access.AccessDeniedException("repository onboarding requires an administrator key");
        if(!request.owner().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")||!request.name().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}")||request.owner().equals("..")||request.name().equals(".."))throw new IllegalArgumentException("owner and name must be safe repository components");
        if(request.type()!=null&&!java.util.Set.of("SERVICE","LIBRARY").contains(request.type().toUpperCase(java.util.Locale.ROOT)))throw new IllegalArgumentException("repository type must be SERVICE or LIBRARY");
        String team=(String)auth.getPrincipal();
        GitRepositoryManager.requireProviderIdentity(request.cloneUrl(),request.owner(),request.name());
        git.validateRegistrationUrl(request.cloneUrl());
        String branch=git.verifyAccessAndBranch(request.cloneUrl(),request.defaultBranch());
        Path mirror=Path.of(props.repositoryRoot()).toAbsolutePath().normalize().resolve(request.owner()).resolve(request.name());
        return transactions.execute(status->{
            // Serialize duplicate registrations before testing configuration, including across replicas.
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,request.owner()+"/"+request.name());
            lifecycle.requireMirrorAvailable(mirror,null);
            var previous=store.findAny(request.owner(),request.name());
            previous.ifPresent(repository->lifecycle.requireSettled(repository.id()));
            boolean reactivated=previous.isPresent()&&store.find(request.owner(),request.name()).isEmpty();
            store.find(request.owner(),request.name()).ifPresent(existing->{
                if(!existing.cloneUrl().equals(request.cloneUrl())||!existing.defaultBranch().equals(branch)
                        ||!existing.repositoryType().equalsIgnoreCase(request.type()==null?"SERVICE":request.type()))
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                            "Repository already registered with different configuration; use the lifecycle update workflow.");
            });
            RepositoryRecord repo=store.insert(request.owner(),request.name(),request.cloneUrl(),team,branch,
                    request.type()==null?"SERVICE":request.type().toUpperCase(java.util.Locale.ROOT),mirror.toString());
            jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES(?,?) ON CONFLICT DO NOTHING",team,repo.id());
            if(reactivated)lifecycle.reactivate(previous.orElseThrow(),repo);
            else onboarding.schedule(repo);
            return repo;
        });
    }
    @GetMapping("/{owner}/{name}/onboarding") public Map<String,Object> onboardingStatus(@PathVariable String owner,
            @PathVariable String name,Authentication auth) {
        return onboarding.status(get(owner,name,auth));
    }
    @PostMapping("/{owner}/{name}/onboarding/retry") public Map<String,Object> retryOnboarding(@PathVariable String owner,
            @PathVariable String name,Authentication auth) {
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))
            throw new org.springframework.security.access.AccessDeniedException("onboarding retry requires an administrator key");
        var repository=get(owner,name,auth);onboarding.retry(repository);return onboarding.status(repository);
    }
    @GetMapping("/{owner}/{name}") public RepositoryRecord get(@PathVariable String owner,@PathVariable String name,Authentication auth) {
        return store.find(owner,name).filter(r->jdbc.queryForObject("SELECT count(*) FROM team_repository_access WHERE team=? AND repository_id=?",Integer.class,auth.getPrincipal(),r.id())>0)
                .orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"repository not found or access denied"));
    }
    @PatchMapping("/{owner}/{name}") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Map<String,Object> update(@PathVariable String owner,@PathVariable String name,
            @RequestBody RepositoryLifecycle.Update request,Authentication auth) {
        requireAdmin(auth);
        var repository=store.findAny(owner,name).orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        return lifecycle.update(repository.id(),String.valueOf(auth.getPrincipal()),request);
    }
    @GetMapping("/by-id/{id}/lifecycle") public Map<String,Object> lifecycleStatus(@PathVariable UUID id,Authentication auth) {
        requireAdmin(auth);return lifecycle.status(id,String.valueOf(auth.getPrincipal()));
    }
    @PostMapping("/by-id/{id}/lifecycle/retry") public Map<String,Object> retryLifecycle(@PathVariable UUID id,Authentication auth) {
        requireAdmin(auth);lifecycle.retry(id,String.valueOf(auth.getPrincipal()));return lifecycle.status(id,String.valueOf(auth.getPrincipal()));
    }
    private static void requireAdmin(Authentication auth) {
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))
            throw new org.springframework.security.access.AccessDeniedException("repository lifecycle updates require an administrator key");
    }
    @DeleteMapping("/{owner}/{name}") public Map<String,Object> disable(@PathVariable String owner,@PathVariable String name,
                                                                        @RequestParam(defaultValue="false") boolean purge,
                                                                        Authentication auth) throws Exception {
        if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_ADMIN")))throw new org.springframework.security.access.AccessDeniedException("repository removal requires an administrator key");
        if(purge) return hardPurge(owner,name);
        RepositoryRecord repo=store.findAny(owner,name).orElseThrow();
        transactions.executeWithoutResult(status->store.disable(repo.id()));
        return Map.of("repository",repo.key(),"status","DISABLED","historyPreserved",true);
    }
    private Map<String,Object> hardPurge(String owner,String name) throws Exception {
        RepositoryRecord repo=store.findAny(owner,name).orElseThrow();
        var claim=leases.tryAcquire(repo.id(),UUID.randomUUID());
        if(claim.isEmpty())throw new IllegalStateException("repository is being indexed; retry purge after the lease expires");
        int generations;
        try(var lease=claim.get()) {
            Integer purged=transactions.execute(status->{
                lease.fenceTransaction();
                return store.purge(repo.id());
            });
            generations=purged==null?0:purged;
        }
        git.deleteMirror(repo);
        retention.removeRepositoryMetadata(repo.id());
        for(int i=0;i<Math.max(1,generations);i++) if(!retention.cleanOne()) break;
        return Map.of("repository",repo.key(),"status","PURGED","historyPreserved",false,"generationsQueued",generations);
    }
    public record OnboardRequest(@NotBlank String owner,@NotBlank String name,@NotBlank String cloneUrl,String defaultBranch,String type) {}
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    public Map<String,String> invalidRegistration() {
        return Map.of("error","Invalid or inaccessible repository configuration; verify the approved URL, credentials and branch.");
    }
}
