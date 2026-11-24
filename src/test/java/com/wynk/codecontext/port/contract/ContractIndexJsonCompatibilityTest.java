package com.wynk.codecontext.port.contract;

import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContractIndexJsonCompatibilityTest {
    private static final String PERSISTED_METADATA = """
            {"contractIdentityVersion":"v1","sourceRange":{"startLine":8,"endLine":12},
             "extractionConfidence":0.85,"evidenceComplete":true,"tags":["rest","openapi"],"nullable":null}
            """;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readsAndRewritesPreviouslyPersistedContractMetadataWithoutChangingJsonValues() throws Exception {
        Map<String, Object> metadata = mapper.readValue(PERSISTED_METADATA,
                new TypeReference<Map<String, Object>>() {});

        assertEquals("v1", metadata.get("contractIdentityVersion"));
        assertEquals(8, ((Number) ((Map<?, ?>) metadata.get("sourceRange")).get("startLine")).intValue());
        assertEquals(0.85, ((Number) metadata.get("extractionConfidence")).doubleValue(), 0.000001);
        assertEquals(true, metadata.get("evidenceComplete"));
        assertNull(metadata.get("nullable"));
        assertEquals(mapper.readTree(PERSISTED_METADATA), mapper.readTree(mapper.writeValueAsString(metadata)));
    }

    @Test
    void readsPreviouslyPersistedExtractorVersionMap() throws Exception {
        String persistedVersions = "{\"maven\":\"1.0.0\",\"rest\":\"1.0.0\"}";
        Map<String, String> versions = mapper.readValue(persistedVersions,
                new TypeReference<Map<String, String>>() {});

        assertEquals(Map.of("maven", "1.0.0", "rest", "1.0.0"), versions);
        assertEquals(mapper.readTree(persistedVersions),
                mapper.readTree(mapper.writeValueAsString(versions)));
    }
}
