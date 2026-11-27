package com.wynk.codecontext.index;

import com.wynk.codecontext.port.contract.*;
import com.wynk.codecontext.port.contract.extract.EnumContractExtractor;
import com.wynk.codecontext.repo.GitRepositoryManager.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationContractExtractionCacheTest {
    @TempDir Path root;
    @Test void fingerprintsCompleteScopesWithAddDeleteRenameAndGlobalInvalidation() {
        var extractor=new EnumContractExtractor();
        var files=List.of(new SourceFile("State.java","ignored","a"),new SourceFile("README.md","ignored","b"));
        String before=GenerationContractExtractionCache.fingerprint(extractor,files);
        assertEquals(before,GenerationContractExtractionCache.fingerprint(extractor,List.of(files.getFirst(),new SourceFile("README.md","ignored","changed"))));
        for(var changed:List.of(List.<SourceFile>of(),List.of(new SourceFile("State.java","ignored","changed")),List.of(new SourceFile("moved/State.java","ignored","a")),
                List.of(files.getFirst(),new SourceFile("pom.xml","ignored","c")),List.of(files.getFirst(),new SourceFile("extra.kt","ignored","d"))))
            assertNotEquals(before,GenerationContractExtractionCache.fingerprint(extractor,changed));
        assertEquals(before,GenerationContractExtractionCache.fingerprint(extractor,List.of(files.get(1),files.getFirst())));
    }
    @Test void reusesRawExtractionButRebuildsCommitSpecificMetadataIdenticallyToCold()throws Exception {
        Files.writeString(root.resolve("State.java"),"package example; public enum State { READY, DONE }");
        var extractor=spy(new EnumContractExtractor());var cache=new MemoryCache();var builder=new ContractIndexBuilder(List.of(extractor));
        builder.build(context("a"),Set.of(),cache);
        var incremental=builder.build(context("b"),Set.of(),cache);
        verify(extractor,times(1)).extractExports(any());verify(extractor,times(1)).extractImports(any());
        var cold=new ContractIndexBuilder(List.of(new EnumContractExtractor())).build(context("b"));
        assertEquals(cold.exports(),incremental.exports());assertEquals(cold.imports(),incremental.imports());assertEquals(cold.indexHash(),incremental.indexHash());
        assertFalse(cache.value.exports().getFirst().metadata().containsKey(ContractEntryMetadata.STABLE_SYMBOL_ID));
    }
    @Test void unavailableAndFailedExtractorsDoNotReuseOrStoreSuccessfulCoverage() {
        var extractor=mock(ContractExtractor.class);when(extractor.id()).thenReturn("enum");when(extractor.version()).thenReturn("1");
        when(extractor.incrementalInputSuffixes()).thenReturn(Set.of(".java"));
        var cache=spy(new MemoryCache());var builder=new ContractIndexBuilder(List.of(extractor));
        assertEquals("unavailable:1",builder.build(context("a"),Set.of(),cache).extractorVersions().get("enum"));
        verifyNoInteractions(cache);
        when(extractor.available()).thenReturn(true);when(extractor.extractExports(any())).thenThrow(new IllegalArgumentException("invalid source"));
        assertEquals("failed:1",builder.build(context("b"),Set.of(),cache).extractorVersions().get("enum"));
        verify(cache,never()).save(any(),any());
    }
    @Test void cacheDatabaseFailurePropagatesInsteadOfBecomingPartialExtractorSuccess() {
        var cache=mock(ContractIndexBuilder.ExtractionCache.class);
        when(cache.find(any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        assertThrows(org.springframework.dao.DataAccessException.class,()->new ContractIndexBuilder(List.of(new EnumContractExtractor())).build(context("a"),Set.of(),cache));
    }
    private RepositoryContext context(String sha){return new RepositoryContext("fixture","library",sha.repeat(40),"main",root);}
    private static class MemoryCache implements ContractIndexBuilder.ExtractionCache {
        ContractIndexBuilder.Extraction value;
        public Optional<ContractIndexBuilder.Extraction> find(ContractExtractor extractor){return Optional.ofNullable(value);}
        public void save(ContractExtractor extractor,ContractIndexBuilder.Extraction extraction){value=extraction;}
    }
}
