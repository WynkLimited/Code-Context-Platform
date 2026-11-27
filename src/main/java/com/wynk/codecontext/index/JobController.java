package com.wynk.codecontext.index;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/index-jobs")
public class JobController {
    private final JdbcTemplate jdbc; public JobController(JdbcTemplate j){jdbc=j;}
    @GetMapping("/{id}") public Object get(@PathVariable String id,Authentication auth){
        return jdbc.queryForList("""
SELECT j.id,j.commit_sha,j.requested_commit_sha,j.generation_id,j.generation_provenance_unverified,j.state,j.attempt,j.error,j.created_at,j.updated_at FROM index_jobs j
        JOIN repositories r ON r.id=j.repository_id JOIN team_repository_access a ON a.repository_id=r.id AND a.team=? WHERE j.id=?""",auth.getPrincipal(),java.util.UUID.fromString(id)).stream().findFirst().orElseThrow();
    }
}
