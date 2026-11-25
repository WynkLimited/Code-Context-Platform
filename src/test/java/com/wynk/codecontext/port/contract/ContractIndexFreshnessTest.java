package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.contract.ContractModels.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContractIndexFreshnessTest {
    private final PostgresContractIndexStore store=new PostgresContractIndexStore(new JdbcTemplate(),
            JsonMapper.builder().build(),new ContractIndexBuilder(List.of()));

    @Test void unsupportedGradleCoverageDoesNotMakeAnOtherwiseCurrentIndexStale() {
        var stored=stored(Map.of("gradle-dependencies","unavailable:NOT_IMPLEMENTED"));
        assertEquals(IndexFreshness.CURRENT,store.freshness(stored),
                "reindexing cannot implement an unavailable resolver; do not schedule perpetual refreshes");
        assertTrue(store.hasCoverageGaps(stored),"CURRENT must not be interpreted as complete Gradle coverage");
    }
    @Test void unknownExtractorMarkersStillRequireReindexing() {
        assertEquals(IndexFreshness.STALE,store.freshness(stored(Map.of("unexpected-extractor","unavailable:NOT_IMPLEMENTED"))));
        assertEquals(IndexFreshness.STALE,store.freshness(stored(Map.of("gradle-dependencies","failed:NOT_IMPLEMENTED"))));
    }
    @Test void corruptedOrOldToolchainIndexesRemainInvalidOrStale() {
        var current=stored(Map.of("gradle-dependencies","unavailable:NOT_IMPLEMENTED"));
        var index=current.index();
        assertEquals(IndexFreshness.INVALID,store.freshness(new PostgresContractIndexStore.StoredIndex(
                current.generationId(),current.repositoryId(),1,index.withIndexHash("corrupt"))));
        var old=new ContractIndex(index.owner(),index.repository(),index.commit(),index.branch(),index.indexSchemaVersion(),
                index.extractorVersions(),index.compatibilityRulesVersion(),"older-generator",index.generatedAt(),null,index.exports(),index.imports());
        old=old.withIndexHash(ContractIndexHasher.hash(old));
        assertEquals(IndexFreshness.STALE,store.freshness(new PostgresContractIndexStore.StoredIndex(
                current.generationId(),current.repositoryId(),1,old)));
    }
    @Test void repositoryCoverageMarkerDoesNotHideInstalledExtractorVersionChanges() {
        var extractor=new com.wynk.codecontext.port.contract.extract.MavenContractExtractor();
        var withMaven=new PostgresContractIndexStore(new JdbcTemplate(),JsonMapper.builder().build(),new ContractIndexBuilder(List.of(extractor)));
        var valid=stored(Map.of(extractor.id(),extractor.version(),"gradle-dependencies","unavailable:NOT_IMPLEMENTED"));
        assertEquals(IndexFreshness.CURRENT,withMaven.freshness(valid));
        assertEquals(IndexFreshness.STALE,withMaven.freshness(stored(Map.of(extractor.id(),"older","gradle-dependencies","unavailable:NOT_IMPLEMENTED"))));
        assertEquals(IndexFreshness.STALE,withMaven.freshness(stored(Map.of("gradle-dependencies","unavailable:NOT_IMPLEMENTED"))));
    }

    private static PostgresContractIndexStore.StoredIndex stored(Map<String,String> versions) {
        var index=new ContractIndex("fixture","gradle","a".repeat(40),"main",ContractModels.INDEX_SCHEMA_VERSION,
                versions,ContractModels.COMPATIBILITY_RULES_VERSION,ContractModels.GENERATOR_VERSION,Instant.EPOCH,null,List.of(),List.of());
        return new PostgresContractIndexStore.StoredIndex(UUID.randomUUID(),UUID.randomUUID(),1,index.withIndexHash(ContractIndexHasher.hash(index)));
    }
}
