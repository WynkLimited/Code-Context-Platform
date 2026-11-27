package com.wynk.codecontext.port.contract.graph;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pure helpers for AMBIGUOUS/RESOLVED relationship evidence payloads used by
 * {@code ConsumerContractIndexRefresher}. Kept free of JDBC so demotion/restore
 * semantics can be unit-tested without PostgreSQL.
 */
public final class ContractRelationshipEvidence {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ContractRelationshipEvidence() {}

    public static ArrayNode asArray(JsonNode evidence) {
        ArrayNode array = MAPPER.createArrayNode();
        if (evidence == null || evidence.isNull() || evidence.isMissingNode()) return array;
        if (evidence.isArray()) {
            evidence.forEach(array::add);
            return array;
        }
        array.add(evidence);
        return array;
    }

    public static boolean isAmbiguous(JsonNode evidence) {
        for (JsonNode elem : asArray(evidence)) {
            if ("AMBIGUOUS".equals(elem.path("relationshipStatus").asText(""))) return true;
        }
        return false;
    }

    public static JsonNode markAmbiguous(JsonNode evidence, int providerCount) {
        ArrayNode array = asArray(evidence);
        ArrayNode filtered = MAPPER.createArrayNode();
        array.forEach(elem -> {
            if (!"AMBIGUOUS".equals(elem.path("relationshipStatus").asText(""))) filtered.add(elem);
        });
        ObjectNode marker = MAPPER.createObjectNode();
        marker.put("relationshipStatus", "AMBIGUOUS");
        marker.put("reason", "multiple published providers match this consumer import");
        marker.put("providerCount", providerCount);
        filtered.add(marker);
        return filtered;
    }

    public static JsonNode clearAmbiguousAndResolve(JsonNode evidence) {
        ArrayNode filtered = MAPPER.createArrayNode();
        asArray(evidence).forEach(elem -> {
            if (!"AMBIGUOUS".equals(elem.path("relationshipStatus").asText(""))) filtered.add(elem);
        });
        ObjectNode resolved = MAPPER.createObjectNode();
        resolved.put("relationshipStatus", "RESOLVED");
        resolved.put("reason", "single published provider remains after re-evaluation");
        filtered.add(resolved);
        return filtered;
    }

    public static double demotedConfidence(double current) {
        return Math.min(current, 0.40d);
    }
}
