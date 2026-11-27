package com.wynk.codecontext.index;

import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractIndexBuilder.Extraction;
import com.wynk.codecontext.port.contract.ContractIndexBuilder.ExtractionCache;
import com.wynk.codecontext.port.contract.ContractModels;
import com.wynk.codecontext.repo.GitRepositoryManager.SourceFile;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Raw, commit-independent extraction only. Enrichment and graph binding always run anew. */
public final class GenerationContractExtractionCache implements ExtractionCache {
    private static final int MAX_JSON_BYTES=16*1024*1024;
    private final JdbcTemplate jdbc;private final JsonMapper json;private final UUID generation;private final List<SourceFile> files;
    public GenerationContractExtractionCache(JdbcTemplate jdbc,JsonMapper json,UUID generation,List<SourceFile> files) {
        this.jdbc=jdbc;this.json=json;this.generation=generation;this.files=List.copyOf(files);
    }
    @Override public Optional<Extraction> find(ContractExtractor extractor) {
        String fingerprint=fingerprint(extractor,files);
        var rows=jdbc.query("""
                SELECT c.generation_id,c.raw_extraction::text FROM contract_extractions c
                JOIN index_generations p ON p.id=c.generation_id
                JOIN index_generations candidate ON candidate.id=? AND candidate.repository_id=p.repository_id
                  AND candidate.repository_revision=p.repository_revision
                WHERE p.published AND c.extractor_id=? AND c.extractor_version=? AND c.input_fingerprint=?
                  AND octet_length(c.raw_extraction::text)<=?
                """,(rs,n)->new Cached(rs.getObject(1,UUID.class),rs.getString(2)),
                generation,extractor.id(),extractor.version(),fingerprint,MAX_JSON_BYTES);
        if(rows.isEmpty())return Optional.empty();
        Extraction extraction;
        try {extraction=json.readValue(rows.getFirst().raw(),Extraction.class);}
        catch(RuntimeException invalid){return Optional.empty();}
        persist(extractor,fingerprint,rows.getFirst().raw(),rows.getFirst().generation());
        return Optional.of(extraction);
    }
    @Override public void save(ContractExtractor extractor,Extraction extraction) {
        String raw=json.writeValueAsString(extraction);
        if(raw.getBytes(StandardCharsets.UTF_8).length>MAX_JSON_BYTES)return; // Optional acceleration, not reduced output.
        persist(extractor,fingerprint(extractor,files),raw,null);
    }
    private void persist(ContractExtractor extractor,String fingerprint,String raw,UUID previous) {
        jdbc.update("""
                INSERT INTO contract_extractions(generation_id,extractor_id,extractor_version,input_fingerprint,raw_extraction,reused_from_generation)
                VALUES(?,?,?,?,?::jsonb,?)
                """,generation,extractor.id(),extractor.version(),fingerprint,raw,previous);
    }
    static String fingerprint(ContractExtractor extractor,List<SourceFile> files) {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            field(digest,"raw-extraction-1");field(digest,ContractModels.GENERATOR_VERSION);field(digest,extractor.id());field(digest,extractor.version());
            var suffixes=extractor.incrementalInputSuffixes().stream().sorted().toList();for(String suffix:suffixes)field(digest,suffix);
            for(var file:files.stream().sorted(Comparator.comparing(SourceFile::path)).toList()) {
                String lower=file.path().toLowerCase(Locale.ROOT);
                if(suffixes.stream().noneMatch(lower::endsWith)&&!PublishedFileDelta.between(Map.of(),Map.of(file.path(),file.blobSha())).globalInputsChanged())continue;
                field(digest,file.path());field(digest,file.blobSha());
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static void field(MessageDigest digest,String value) {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
    }
    private record Cached(UUID generation,String raw) {}
}
