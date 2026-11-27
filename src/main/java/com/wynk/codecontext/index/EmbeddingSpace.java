package com.wynk.codecontext.index;

import org.springframework.ai.embedding.EmbeddingModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

/** Provider digest and effective options identify vectors; a mutable model tag alone does not. */
public record EmbeddingSpace(String model,String digest,int dimensions,int contextTokens) {
    public interface IdentifiedModel {EmbeddingSpace embeddingSpace(int dimensions);}
    public EmbeddingSpace {
        if(model==null||!model.matches("[A-Za-z0-9][A-Za-z0-9_./:-]{0,255}"))throw new IllegalArgumentException("invalid embedding model identity");
        if(digest==null||!digest.matches("(?:sha256:)?[A-Fa-f0-9]{64}"))throw new IllegalArgumentException("embedding model digest unavailable");
        digest=digest.replaceFirst("^sha256:","").toLowerCase(Locale.ROOT);
        if(dimensions<1||contextTokens<512)throw new IllegalArgumentException("invalid embedding space dimensions/context");
    }
    public static EmbeddingSpace current(EmbeddingModel model,int dimensions) {
        if(!(model instanceof IdentifiedModel identified))throw new IllegalStateException("embedding provider does not expose model provenance");
        return identified.embeddingSpace(dimensions);
    }
    public Map<String,Object> metadata() {
        return Map.of("provider","ollama","model",model,"digest",digest,"dimensions",dimensions,
                "contextTokens",contextTokens,"truncate",false,"policy","ollama-embedding-v1");
    }
    public String fingerprint() {
        try {
            String canonical="ollama-embedding-v1\n"+model+"\n"+digest+"\n"+dimensions+"\n"+contextTokens+"\ntruncate=false";
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public void requireCurrent(EmbeddingModel model) {
        if(!equals(current(model,dimensions)))throw new IllegalStateException("embedding model changed during processing; retry after model provisioning is stable");
    }
}
