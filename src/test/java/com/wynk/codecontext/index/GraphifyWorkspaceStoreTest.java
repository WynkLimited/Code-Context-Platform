package com.wynk.codecontext.index;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.repo.GitRepositoryManager.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class GraphifyWorkspaceStoreTest {
    @TempDir Path root;
    private final UUID repository=UUID.randomUUID();
    @Test void reusesImmutableSourcesAndOnlyTrustedAstCachesWhileKeepingGenerationsIsolated()throws Exception {
        var store=store();var original=List.of(file("alpha.py","def alpha(): return 1\n","a"),file("beta.py","def beta(): return 2\n","b"));
        var before=store.prepare(repository,UUID.randomUUID(),"1".repeat(40),1,null,original);cache(before);store.complete(before);
        var changed=List.of(file("alpha.py","def alpha(): return 3\n","c"),original.get(1));
        var after=store.prepare(repository,UUID.randomUUID(),"2".repeat(40),1,new GraphifyWorkspaceStore.Baseline(before.generation(),before.commit()),changed);
        assertEquals(1,after.reusedSources());assertEquals(1,after.writtenSources());assertEquals("INCREMENTAL_AST_GLOBAL_RELINK",after.mode());
        assertTrue(Files.isSameFile(before.sources().resolve("beta.py"),after.sources().resolve("beta.py")));
        assertFalse(Files.isSameFile(before.sources().resolve("alpha.py"),after.sources().resolve("alpha.py")));
        assertEquals(original.getFirst().content(),Files.readString(before.sources().resolve("alpha.py")));
        assertTrue(Files.isRegularFile(after.output().resolve("cache/ast/epoch/cached.json")));
        assertFalse(Files.exists(after.output().resolve("graph.json")),"previous resolved edges must not survive into the global relink");
        assertFalse(after.output().startsWith(after.sources()));assertFalse(after.home().startsWith(after.sources()));
    }
    @Test void rejectsCorruptCacheOrWrongRevisionAndGloballyInvalidatesBuildChanges()throws Exception {
        var store=store();var original=List.of(file("alpha.py","def alpha(): return 1\n","a"));
        var before=store.prepare(repository,UUID.randomUUID(),"1".repeat(40),1,null,original);cache(before);store.complete(before);
        var baseline=new GraphifyWorkspaceStore.Baseline(before.generation(),before.commit());
        var build=store.prepare(repository,UUID.randomUUID(),"2".repeat(40),1,baseline,List.of(original.getFirst(),file("pom.xml","<project/>\n","b")));
        assertEquals("FULL_GLOBAL_INPUT_CHANGE",build.mode());assertFalse(Files.exists(build.output().resolve("cache")));
        var revised=store.prepare(repository,UUID.randomUUID(),"2".repeat(40),2,baseline,original);
        assertEquals("FULL_NO_VALID_BASELINE",revised.mode());assertEquals(0,revised.reusedSources());
        Files.writeString(before.output().resolve("cache/ast/epoch/cached.json"),"tampered cache");
        var corrupted=store.prepare(repository,UUID.randomUUID(),"2".repeat(40),1,baseline,original);
        assertEquals("FULL_NO_VALID_BASELINE",corrupted.mode());assertFalse(Files.exists(corrupted.output().resolve("cache")));
    }
    @Test void handlesRenamedSourcesAndRemovesOnlyTheRequestedGenerationWithoutFollowingLinks()throws Exception {
        var store=store();var before=store.prepare(repository,UUID.randomUUID(),"1".repeat(40),1,null,List.of(file("old.py","def alpha(): return 1\n","a")));cache(before);store.complete(before);
        var moved=store.prepare(repository,UUID.randomUUID(),"2".repeat(40),1,new GraphifyWorkspaceStore.Baseline(before.generation(),before.commit()),List.of(file("new.py","def alpha(): return 1\n","a")));
        assertEquals(1,moved.reusedSources());assertEquals(1,moved.delta().renamed().size());assertFalse(Files.exists(moved.sources().resolve("old.py")));
        Path sentinel=root.resolve("outside.txt");Files.writeString(sentinel,"must remain");Files.createSymbolicLink(moved.root().resolve("outside-link"),sentinel);
        store.remove(repository,moved.generation());store.remove(repository,moved.generation());
        assertEquals("must remain",Files.readString(sentinel));assertTrue(Files.exists(before.root()));
        assertThrows(java.io.IOException.class,()->store.prepare(repository,UUID.randomUUID(),"3".repeat(40),1,null,List.of(file("../escape.py","bad","b"))));
    }
    private static SourceFile file(String path,String content,String sha){return new SourceFile(path,content,sha.repeat(40));}
    private static void cache(GraphifyWorkspaceStore.Workspace workspace)throws Exception {
        Files.createDirectories(workspace.output().resolve("cache/ast/epoch"));Files.writeString(workspace.output().resolve("cache/ast/epoch/cached.json"),"{\"trusted\":true}");
        Files.writeString(workspace.output().resolve("graph.json"),"{\"nodes\":[],\"links\":[]}");
    }
    private GraphifyWorkspaceStore store(){return new GraphifyWorkspaceStore(new CodeContextProperties(root.resolve("mirrors").toString(),root.resolve("sources").toString(),root.resolve("zoekt").toString(),"t=k","t=a","fixture","","github.com","http://localhost",1024,1048576,6000,1,30,
            new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("/trusted/graphify",true)),JsonMapper.builder().build());}
}
