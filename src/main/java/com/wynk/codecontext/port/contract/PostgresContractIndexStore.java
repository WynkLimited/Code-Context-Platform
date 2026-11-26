package com.wynk.codecontext.port.contract;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import com.wynk.codecontext.port.contract.ContractModels.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Reads immutable contract indexes from the platform's published and historical generations. */
public final class PostgresContractIndexStore {
    private static final TypeReference<Map<String,Object>> OBJECT_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String,String>> STRING_MAP = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;
    private final Map<String,String> currentExtractorVersions;

    public PostgresContractIndexStore(JdbcTemplate jdbc, JsonMapper mapper, ContractIndexBuilder builder) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        var versions = new TreeMap<String,String>();
        builder.extractors().forEach(e -> versions.put(e.id(), e.available()?e.version():"unavailable:"+e.version()));
        this.currentExtractorVersions = Map.copyOf(versions);
    }

    public Optional<StoredIndex> published(UUID repositoryId) {
        List<Header> rows=jdbc.query("SELECT g.id,g.repository_id,g.generation,g.commit_sha,r.owner,r.name,r.default_branch,m.index_schema_version,m.extractor_versions::text,m.compatibility_rules_version,m.generator_version,m.generated_at,m.index_hash FROM index_generations g JOIN repositories r ON r.id=g.repository_id JOIN contract_index_metadata m ON m.generation_id=g.id WHERE g.repository_id=? AND g.published",this::header,repositoryId);
        return rows.stream().findFirst().map(this::readIndex);
    }

    /** Previous successful generation, excluding failed/unpublished attempts. */
    public Optional<StoredIndex> previousSuccessful(UUID repositoryId, int beforeGeneration) {
        List<Header> rows=jdbc.query("""
SELECT g.id,g.repository_id,g.generation,g.commit_sha,r.owner,r.name,r.default_branch,m.index_schema_version,m.extractor_versions::text,m.compatibility_rules_version,m.generator_version,m.generated_at,m.index_hash
FROM index_generations g JOIN repositories r ON r.id=g.repository_id JOIN contract_index_metadata m ON m.generation_id=g.id
WHERE g.repository_id=? AND g.repository_revision=r.configuration_revision AND g.generation < ? AND EXISTS (SELECT 1 FROM index_jobs j WHERE j.repository_id=g.repository_id AND j.generation_id=g.id AND j.commit_sha=g.commit_sha AND j.state='SUCCEEDED')
ORDER BY g.generation DESC LIMIT 1
""", this::header, repositoryId, beforeGeneration);
        return rows.stream().findFirst().map(this::readIndex);
    }

    public Optional<StoredIndex> latest(UUID repositoryId) { return published(repositoryId); }

    public IndexFreshness freshness(StoredIndex stored) {
        if (stored == null) return IndexFreshness.MISSING;
        ContractIndex index = stored.index();
        if (index.commit() == null || !index.commit().matches("(?i)[0-9a-f]{40}") || index.indexHash() == null
                || !index.indexHash().equals(ContractIndexHasher.hash(index))) return IndexFreshness.INVALID;
        if (!ContractModels.INDEX_SCHEMA_VERSION.equals(index.indexSchemaVersion())
                || !ContractModels.GENERATOR_VERSION.equals(index.generatorVersion())
                || !ContractModels.COMPATIBILITY_RULES_VERSION.equals(index.compatibilityRulesVersion())) return IndexFreshness.STALE;
        // Repository-specific unsupported build coverage is not an installed extractor version.
        // Keep it visible via hasCoverageGaps without making every subsequent reindex stale.
        var installedKeys=new HashSet<>(index.extractorVersions().keySet());
        index.extractorVersions().forEach((key,value)->{
            if(isRepositoryCoverageGap(key,value))installedKeys.remove(key);
        });
        if (!installedKeys.equals(currentExtractorVersions.keySet())) return IndexFreshness.STALE;
        for (var recorded : index.extractorVersions().entrySet()) {
            if(isRepositoryCoverageGap(recorded.getKey(),recorded.getValue()))continue;
            String current=currentExtractorVersions.get(recorded.getKey());
            if (current==null || !Objects.equals(current,recorded.getValue()) || recorded.getValue().startsWith("failed:")) return IndexFreshness.STALE;
        }
        return IndexFreshness.CURRENT;
    }

    private static boolean isRepositoryCoverageGap(String key,String version) {
        return "gradle-dependencies".equals(key) && "unavailable:NOT_IMPLEMENTED".equals(version);
    }

    public boolean hasCoverageGaps(StoredIndex stored) {
        return stored==null || stored.index().extractorVersions().values().stream().anyMatch(v->v.startsWith("unavailable:")||v.startsWith("failed:"));
    }

    private Header header(ResultSet rs,int row) throws SQLException {
        return new Header(rs.getObject("id",UUID.class),rs.getObject("repository_id",UUID.class),rs.getInt("generation"),rs.getString("commit_sha"),rs.getString("owner"),rs.getString("name"),rs.getString("default_branch"),rs.getString("index_schema_version"),rs.getString("extractor_versions"),rs.getString("compatibility_rules_version"),rs.getString("generator_version"),rs.getTimestamp("generated_at").toInstant(),rs.getString("index_hash"));
    }
    private StoredIndex readIndex(Header h) {
        List<ContractExport> exports = jdbc.query("SELECT contract_type,canonical_key,symbol,file_path,content_hash,dependency_version,metadata::text FROM contracts WHERE generation_id=? AND role='EXPORT' ORDER BY canonical_key,file_path,line,id", this::readExport, h.generation);
        List<ContractImport> imports = jdbc.query("SELECT contract_type,canonical_key,symbol,file_path,content_hash,dependency_version,metadata::text FROM contracts WHERE generation_id=? AND role='IMPORT' ORDER BY canonical_key,file_path,line,id", this::readImport, h.generation);
        Map<String,String> extractorVersions = parseMap(h.extractorVersions, STRING_MAP);
        return new StoredIndex(h.generation, h.repositoryId, h.generationNumber, new ContractIndex(h.owner,h.name,h.commit,h.branch,h.schema,extractorVersions,h.rules,h.generator,h.generated,h.hash,exports,imports));
    }

    private ContractExport readExport(ResultSet rs, int row) throws SQLException {
        ContractType type = ContractType.valueOf(rs.getString("contract_type"));
        String key=rs.getString("canonical_key"), version=rs.getString("dependency_version"); Map<String,Object> metadata=parseMap(rs.getString("metadata"),OBJECT_MAP);
        return new ContractExport(identity(type,key,version,metadata),type,rs.getString("file_path"),rs.getString("symbol"),rs.getString("content_hash"),metadata);
    }
    private ContractImport readImport(ResultSet rs, int row) throws SQLException {
        ContractType type = ContractType.valueOf(rs.getString("contract_type"));
        String key=rs.getString("canonical_key"), version=rs.getString("dependency_version"); Map<String,Object> metadata=parseMap(rs.getString("metadata"),OBJECT_MAP);
        return new ContractImport(identity(type,key,version,metadata),type,rs.getString("file_path"),rs.getString("symbol"),version,metadata);
    }

    private ContractIdentity identity(ContractType type, String canonical, String version,Map<String,Object> metadata) {
        String value = canonical.substring(canonical.indexOf(':')+1);
        Object identityVersion=metadata.get("contractIdentityVersion");
        String contractVersion=identityVersion instanceof String s?s:version;
        return switch(type) {
            case PROTO -> { String pkg=String.valueOf(metadata.getOrDefault("protoPackage","")); String msg=String.valueOf(metadata.getOrDefault("message", "")); if(msg.isBlank()){String prefix=pkg.isBlank()?"":pkg+".";msg=value.startsWith(prefix)?value.substring(prefix.length()):value;} yield ContractIdentity.ofProto(pkg,msg); }
            case ENUM -> { int dot=value.lastIndexOf('.'); yield ContractIdentity.ofEnum(dot<0?null:value.substring(0,dot),dot<0?value:value.substring(dot+1)); }
            case GRPC -> { String pkg=String.valueOf(metadata.getOrDefault("protoPackage","")); String service=String.valueOf(metadata.getOrDefault("service","")); String operation=value.substring(value.lastIndexOf('/')+1); if(service.contains(".")){int dot=service.lastIndexOf('.'); if(pkg.isBlank())pkg=service.substring(0,dot); service=service.substring(dot+1);} if(service.isBlank()){int slash=value.lastIndexOf('/'),dot=slash<0?-1:value.lastIndexOf('.',slash); service=dot<0?(slash<0?value:value.substring(0,slash)):value.substring(dot+1,slash);if(pkg.isBlank()&&dot>0)pkg=value.substring(0,dot);} yield ContractIdentity.ofGrpc(pkg,service,operation); }
            case MAVEN -> { int colon=value.indexOf(':'); yield ContractIdentity.ofMaven(colon<0?"":value.substring(0,colon),colon<0?value:value.substring(colon+1),contractVersion); }
            case KAFKA -> ContractIdentity.ofKafka(value);
            case REST -> { int colon=value.indexOf(':'); yield ContractIdentity.ofRest(colon<0?"*":value.substring(0,colon),colon<0?value:value.substring(colon+1)); }
            case CONFIG -> ContractIdentity.ofConfig(value);
            case CACHE -> ContractIdentity.ofCache(value);
            case DATABASE -> ContractIdentity.ofDatabase(value);
            case STRING_FORMAT -> ContractIdentity.ofStringFormat(value);
        };
    }
    private <T> T parseMap(String json, TypeReference<T> type) {
        try { return mapper.readValue(json, type); } catch(Exception e) { throw new IllegalStateException("invalid persisted contract index JSON",e); }
    }
    public record StoredIndex(UUID generationId, UUID repositoryId, int generationNumber, ContractIndex index) {}
    private record Header(UUID generation,UUID repositoryId,int generationNumber,String commit,String owner,String name,String branch,String schema,String extractorVersions,String rules,String generator,java.time.Instant generated,String hash){}
}
