package com.wynk.codecontext.repo;

import com.wynk.codecontext.model.RepositoryRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RepositoryStore {
    private final JdbcTemplate jdbc;
    public RepositoryStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public RepositoryRecord insert(String owner, String name, String url, String team, String branch, String type, String path) {
        // Soft-disabled rows keep their identity; only the owning team may re-onboard without a hard purge.
        var existing=jdbc.query("SELECT team FROM repositories WHERE owner=? AND name=?",(rs,n)->rs.getString(1),owner,name);
        if(!existing.isEmpty()&&!existing.get(0).equals(team))
            throw new AccessDeniedException("repository identity belongs to another team; hard-purge required before reclaim");
        try {
            return jdbc.queryForObject("""
INSERT INTO repositories(owner,name,clone_url,team,default_branch,repository_type,mirror_path)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(owner,name) DO UPDATE SET clone_url=excluded.clone_url,
                default_branch=excluded.default_branch,repository_type=excluded.repository_type,mirror_path=excluded.mirror_path,enabled=true
                WHERE repositories.team=excluded.team
                RETURNING id,owner,name,clone_url,team,default_branch,repository_type,mirror_path""", (rs,n)->map(rs), owner,name,url,team,branch,type,path);
        } catch(org.springframework.dao.EmptyResultDataAccessException contested) {
            throw new AccessDeniedException("repository identity belongs to another team; hard-purge required before reclaim");
        }
    }
    public Optional<RepositoryRecord> find(String owner, String name) {
        return jdbc.query("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path FROM repositories WHERE owner=? AND name=? AND enabled", (rs,n)->map(rs), owner,name).stream().findFirst();
    }
    /** Includes soft-disabled rows; used for authorized hard purge. */
    public Optional<RepositoryRecord> findAny(String owner, String name) {
        return jdbc.query("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path FROM repositories WHERE owner=? AND name=?", (rs,n)->map(rs), owner,name).stream().findFirst();
    }
    /** Provider identities are case-insensitive; preserve the registered identifier in all responses. */
    public Optional<RepositoryRecord> findProviderIdentity(String fullName) {
        var matches=jdbc.query("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path FROM repositories WHERE lower(owner||'/'||name)=lower(?) AND enabled",(rs,n)->map(rs),fullName);
        if(matches.size()>1)throw new IllegalStateException("ambiguous registered Git provider identity");
        return matches.stream().findFirst();
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
    /**
     * Authorized hard deletion: retention protections do not apply. Enqueues Zoekt cleanup for every
     * generation, then removes PostgreSQL state (chunks/embeddings/contracts cascade from generations).
     */
    public int purge(UUID repositoryId) {
        jdbc.update("UPDATE repositories SET enabled=false WHERE id=?",repositoryId);
        jdbc.update("UPDATE index_jobs SET state='FAILED',error='repository purged',updated_at=now() WHERE repository_id=? AND state IN ('QUEUED','RUNNING')",repositoryId);
        jdbc.update("DELETE FROM contract_links l USING contracts p,index_generations pg WHERE l.provider_contract=p.id AND p.generation_id=pg.id AND pg.repository_id=?",repositoryId);
        jdbc.update("DELETE FROM contract_links l USING contracts c,index_generations cg WHERE l.consumer_contract=c.id AND c.generation_id=cg.id AND cg.repository_id=?",repositoryId);
        jdbc.update("DELETE FROM team_repository_access WHERE repository_id=?",repositoryId);
        var identity=jdbc.query("SELECT owner,name FROM repositories WHERE id=? FOR UPDATE",(rs,n)->new String[]{rs.getString(1),rs.getString(2)},repositoryId);
        if(identity.isEmpty())return 0;
        String owner=identity.get(0)[0],name=identity.get(0)[1];
        // Drop dependent rows that lack ON DELETE CASCADE before generations/repository removal.
        jdbc.update("DELETE FROM cross_repo_impact_analyses WHERE origin_repository_id=? OR before_generation_id IN (SELECT id FROM index_generations WHERE repository_id=?) OR after_generation_id IN (SELECT id FROM index_generations WHERE repository_id=?)",repositoryId,repositoryId,repositoryId);
        jdbc.update("DELETE FROM contract_refresh_jobs WHERE origin_repository_id=? OR repository_id=? OR origin_generation_id IN (SELECT id FROM index_generations WHERE repository_id=?)",repositoryId,repositoryId,repositoryId);
        jdbc.update("DELETE FROM webhook_deliveries WHERE repository_id=?",repositoryId);
        var generations=jdbc.query("SELECT id FROM index_generations WHERE repository_id=?",(rs,n)->rs.getObject(1,UUID.class),repositoryId);
        for(UUID generation:generations)
            jdbc.update("INSERT INTO generation_cleanup_outbox(generation_id,repository_id,owner,name) VALUES(?,?,?,?) ON CONFLICT(generation_id) DO UPDATE SET state='QUEUED',attempt=0,available_at=now(),error=NULL,updated_at=now()",generation,repositoryId,owner,name);
        jdbc.update("DELETE FROM index_jobs WHERE repository_id=?",repositoryId);
        jdbc.update("DELETE FROM index_generations WHERE repository_id=?",repositoryId);
        jdbc.update("DELETE FROM repositories WHERE id=?",repositoryId);
        return generations.size();
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
