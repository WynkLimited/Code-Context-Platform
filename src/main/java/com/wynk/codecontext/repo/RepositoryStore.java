package com.wynk.codecontext.repo;

import com.wynk.codecontext.model.RepositoryRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositoryStore {
    private final JdbcTemplate jdbc;
    public RepositoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public RepositoryRecord insert(String owner, String name, String url, String team, String branch, String type, String path) {
        return jdbc.queryForObject("""
INSERT INTO repositories(owner,name,clone_url,team,default_branch,repository_type,mirror_path)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(owner,name) DO UPDATE SET clone_url=excluded.clone_url,team=excluded.team,
                default_branch=excluded.default_branch,repository_type=excluded.repository_type,mirror_path=excluded.mirror_path,enabled=true
                RETURNING id,owner,name,clone_url,team,default_branch,repository_type,mirror_path""", (rs,n)->map(rs), owner,name,url,team,branch,type,path);
    }
    public Optional<RepositoryRecord> find(String owner, String name) {
        return jdbc.query("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path FROM repositories WHERE owner=? AND name=? AND enabled", (rs,n)->map(rs), owner,name).stream().findFirst();
    }
    public List<RepositoryRecord> findAccessible(String team) {
        return jdbc.query("""
SELECT r.id,r.owner,r.name,r.clone_url,r.team,r.default_branch,r.repository_type,r.mirror_path
                FROM repositories r JOIN team_repository_access a ON a.repository_id=r.id
                WHERE a.team=? AND r.enabled ORDER BY r.owner,r.name""", (rs,n)->map(rs), team);
    }
    public UUID id(String owner,String name) { return find(owner,name).orElseThrow().id(); }
    public void disable(UUID repositoryId) {
        jdbc.update("UPDATE repositories SET enabled=false WHERE id=?",repositoryId);
        jdbc.update("UPDATE index_jobs SET state='FAILED',error='repository disabled',updated_at=now() WHERE repository_id=? AND state='QUEUED'",repositoryId);
        jdbc.update("DELETE FROM contract_links l USING contracts p,index_generations pg WHERE l.provider_contract=p.id AND p.generation_id=pg.id AND pg.repository_id=?",repositoryId);
        jdbc.update("DELETE FROM contract_links l USING contracts c,index_generations cg WHERE l.consumer_contract=c.id AND c.generation_id=cg.id AND cg.repository_id=?",repositoryId);
        jdbc.update("DELETE FROM team_repository_access WHERE repository_id=?",repositoryId);
    }
    public Optional<String> currentCommit(UUID repositoryId) {
        return jdbc.query("SELECT commit_sha FROM index_generations WHERE repository_id=? AND published",(rs,n)->rs.getString(1),repositoryId).stream().findFirst();
    }
    public Optional<UUID> currentGeneration(UUID repositoryId) {
        return jdbc.query("SELECT id FROM index_generations WHERE repository_id=? AND published",(rs,n)->rs.getObject(1,UUID.class),repositoryId).stream().findFirst();
    }
    private static RepositoryRecord map(java.sql.ResultSet r) throws java.sql.SQLException {
        return new RepositoryRecord(r.getObject("id",UUID.class),r.getString("owner"),r.getString("name"),r.getString("clone_url"),r.getString("team"),r.getString("default_branch"),r.getString("repository_type"),r.getString("mirror_path"));
    }
}
