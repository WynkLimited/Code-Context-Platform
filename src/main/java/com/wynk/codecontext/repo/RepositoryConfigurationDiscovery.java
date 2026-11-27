package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Observes tracked configuration; repository content never supplies administrative configuration. */
@Service
public final class RepositoryConfigurationDiscovery {
    private static final Map<String,String> MANIFESTS=Map.ofEntries(
            Map.entry("pom.xml","MAVEN"),Map.entry("build.gradle","GRADLE"),Map.entry("build.gradle.kts","GRADLE"),
            Map.entry("settings.gradle","GRADLE"),Map.entry("settings.gradle.kts","GRADLE"),Map.entry("package.json","NODE"),
            Map.entry("pyproject.toml","PYTHON"),Map.entry("requirements.txt","PYTHON"),Map.entry("go.mod","GO"),
            Map.entry("Cargo.toml","RUST"),Map.entry(".code-context.yml","CONTEXT_METADATA"),Map.entry(".code-context.yaml","CONTEXT_METADATA"));
    private static final Map<String,String> LANGUAGES=Map.ofEntries(Map.entry("java","JAVA"),Map.entry("kt","KOTLIN"),
            Map.entry("kts","KOTLIN"),Map.entry("py","PYTHON"),Map.entry("ts","TYPESCRIPT"),Map.entry("tsx","TYPESCRIPT"),
            Map.entry("js","JAVASCRIPT"),Map.entry("jsx","JAVASCRIPT"),Map.entry("go","GO"),Map.entry("rs","RUST"),Map.entry("cs","CSHARP"));
    private static final int ENTRY_LIMIT=25000,MANIFEST_LIMIT=512;
    private final JdbcTemplate jdbc;private final CodeContextProperties properties;private final TransactionTemplate transactions;
    public RepositoryConfigurationDiscovery(JdbcTemplate jdbc,CodeContextProperties properties,PlatformTransactionManager tx) {
        this.jdbc=jdbc;this.properties=properties;this.transactions=new TransactionTemplate(tx);
    }
    public Map<String,Object> discover(String owner,String name,String team) {
        return transactions.execute(status->{
            // Serialize ACL/identity changes and mirror deletion until the immutable tree is read.
            var rows=jdbc.queryForList("""
                SELECT r.id,r.owner,r.name,r.default_branch,r.repository_type,r.mirror_path,r.configuration_revision
                FROM repositories r JOIN team_repository_access a ON a.repository_id=r.id
                WHERE r.owner=? AND r.name=? AND r.enabled AND a.team=? FOR SHARE OF r
                """,owner,name,team);
            if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"repository not found or access denied");
            var repository=rows.getFirst();UUID id=(UUID)repository.get("id");
            var generations=jdbc.queryForList("SELECT id,commit_sha FROM index_generations WHERE repository_id=? AND published ORDER BY generation DESC LIMIT 1",id);
            if(generations.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"configuration discovery requires a published generation");
            var generation=generations.getFirst();Path root=Path.of(properties.repositoryRoot()).toAbsolutePath().normalize();
            Path expected=root.resolve(owner).resolve(name),stored=Path.of(String.valueOf(repository.get("mirror_path"))).toAbsolutePath().normalize();
            if(!expected.equals(stored))throw new ResponseStatusException(HttpStatus.CONFLICT,"repository mirror requires operator reconciliation");
            try {
                if(!stored.toRealPath().startsWith(root.toRealPath()))throw new IllegalStateException("mirror is outside managed storage");
                var observed=inspect(stored,String.valueOf(generation.get("commit_sha")));
                return Map.of("repository",owner+"/"+name,"repositoryId",id,"configurationRevision",repository.get("configuration_revision"),
                        "defaultBranch",repository.get("default_branch"),"repositoryType",repository.get("repository_type"),
                        "generationId",generation.get("id"),"commit",generation.get("commit_sha"),"discovery",observed,
                        "configurationPolicy","Repository metadata is observed only; administrative configuration uses the authorized lifecycle API.");
            }catch(Exception failure) {
                if(failure instanceof ResponseStatusException response)throw response;
                throw new ResponseStatusException(HttpStatus.CONFLICT,"published Git tree is unavailable for configuration discovery");
            }
        });
    }
    static Snapshot inspect(Path root,String commit)throws Exception {
        if(!commit.matches("[0-9a-fA-F]{40}"))throw new IllegalArgumentException("discovery requires an exact Git commit");
        var manifests=new ArrayList<Manifest>();var languages=new TreeSet<String>();var systems=new TreeSet<String>();
        int scanned=0;boolean truncated=false,manifestLimit=false;
        try(var git=Git.open(root.toFile());var walk=new org.eclipse.jgit.revwalk.RevWalk(git.getRepository())) {
            var revision=walk.parseCommit(org.eclipse.jgit.lib.ObjectId.fromString(commit));
            try(var tree=new TreeWalk(git.getRepository())) {
                tree.addTree(revision.getTree());tree.setRecursive(true);
                while(tree.next()) {
                    if(scanned==ENTRY_LIMIT){truncated=true;break;}scanned++;
                    var mode=tree.getFileMode(0);if(!FileMode.REGULAR_FILE.equals(mode)&&!FileMode.EXECUTABLE_FILE.equals(mode))continue;
                    String path=tree.getPathString(),file=path.substring(path.lastIndexOf('/')+1);
                    String language=LANGUAGES.get(file.substring(file.lastIndexOf('.')+1));if(language!=null)languages.add(language);
                    String kind=MANIFESTS.get(file);if(kind==null)continue;
                    if(!"CONTEXT_METADATA".equals(kind))systems.add(kind);
                    if(manifests.size()==MANIFEST_LIMIT){manifestLimit=true;continue;}
                    var object=git.getRepository().open(tree.getObjectId(0));
                    manifests.add(new Manifest(path,kind,object.getSize(),tree.getObjectId(0).name()));
                }
            }
        }
        return new Snapshot(List.copyOf(manifests),List.copyOf(systems),List.copyOf(languages),scanned,truncated,manifestLimit);
    }
    record Manifest(String path,String kind,long bytes,String blobSha) {}
    record Snapshot(List<Manifest> manifests,List<String> buildSystems,List<String> languages,int scannedEntries,boolean treeTruncated,boolean manifestsTruncated) {}
}
