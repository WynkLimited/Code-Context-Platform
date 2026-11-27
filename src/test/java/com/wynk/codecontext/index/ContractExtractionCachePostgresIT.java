package com.wynk.codecontext.index;

import com.wynk.codecontext.port.contract.*;
import com.wynk.codecontext.port.contract.extract.EnumContractExtractor;
import com.wynk.codecontext.repo.GitRepositoryManager.SourceFile;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL cache eligibility and raw extraction/enrichment equivalence. */
class ContractExtractionCachePostgresIT {
    @TempDir Path root;
    @Test void reusesOnlyMatchingPublishedRepositoryRevisionAndPreservesColdContracts()throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        String user=System.getenv().getOrDefault("DATABASE_USER","code_context"),password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        assertTrue(admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"));
        String schema="contract_cache_"+UUID.randomUUID().toString().replace("-","");admin.execute("CREATE SCHEMA "+schema);
        try {
            var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).placeholders(Map.of("vectorDimensions","1024")).load().migrate();
            var jdbc=new JdbcTemplate(ds);var json=JsonMapper.builder().build();var extractor=new EnumContractExtractor();
            UUID repo=repository(jdbc,"provider"),foreign=repository(jdbc,"foreign");
            String content="package example; public enum State { READY, DONE }";Files.writeString(root.resolve("State.java"),content);
            var files=List.of(new SourceFile("State.java",content,"a".repeat(40)));
            UUID first=generation(jdbc,repo,1,1),second=generation(jdbc,repo,2,1);
            var builder=new ContractIndexBuilder(List.of(extractor));
            builder.build(context("a"),Set.of(),new GenerationContractExtractionCache(jdbc,json,first,files));
            jdbc.update("UPDATE index_generations SET published=true WHERE id=?",first);
            var warm=builder.build(context("b"),Set.of(),new GenerationContractExtractionCache(jdbc,json,second,files));
            var cold=builder.build(context("b"));assertEquals(cold.exports(),warm.exports());assertEquals(cold.imports(),warm.imports());assertEquals(cold.indexHash(),warm.indexHash());
            assertEquals(first,jdbc.queryForObject("SELECT reused_from_generation FROM contract_extractions WHERE generation_id=?",UUID.class,second));
            assertFalse(jdbc.queryForObject("SELECT raw_extraction::text FROM contract_extractions WHERE generation_id=?",String.class,second).contains("stableSymbolId"));
            UUID other=generation(jdbc,foreign,1,1),revised=generation(jdbc,repo,3,2);
            assertTrue(new GenerationContractExtractionCache(jdbc,json,other,files).find(extractor).isEmpty(),"another repository cannot seed this cache");
            assertTrue(new GenerationContractExtractionCache(jdbc,json,revised,files).find(extractor).isEmpty(),"configuration revisions cannot share raw state");
            assertTrue(new GenerationContractExtractionCache(jdbc,json,generation(jdbc,repo,4,1),List.of()).find(extractor).isEmpty(),"deletion invalidates input closure");
            jdbc.update("UPDATE contract_extractions SET raw_extraction='{}'::jsonb WHERE generation_id=?",first);
            assertTrue(new GenerationContractExtractionCache(jdbc,json,generation(jdbc,repo,5,1),files).find(extractor).isEmpty(),"malformed cached output forces fresh extraction");
            jdbc.update("UPDATE index_generations SET published=false WHERE id=?",first);
            assertTrue(new GenerationContractExtractionCache(jdbc,json,generation(jdbc,repo,6,1),files).find(extractor).isEmpty(),"unpublished candidates cannot become baselines");
            jdbc.update("DELETE FROM index_generations WHERE id=?",second);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM contract_extractions WHERE generation_id=?",Integer.class,second));
        }finally {admin.execute("DROP SCHEMA "+schema+" CASCADE");}
    }
    private RepositoryContext context(String commit){return new RepositoryContext("fixture","provider",commit.repeat(40),"main",root);}
    private static UUID repository(JdbcTemplate jdbc,String name){return jdbc.queryForObject("INSERT INTO repositories(owner,name,clone_url,team,mirror_path) VALUES('fixture',?,'','team','') RETURNING id",UUID.class,name);}
    private static UUID generation(JdbcTemplate jdbc,UUID repo,int ordinal,long revision){return jdbc.queryForObject("INSERT INTO index_generations(repository_id,commit_sha,generation,published,repository_revision) VALUES(?,?,?,false,?) RETURNING id",UUID.class,repo,(ordinal==1?"a":"b").repeat(40),ordinal,revision);}
}
