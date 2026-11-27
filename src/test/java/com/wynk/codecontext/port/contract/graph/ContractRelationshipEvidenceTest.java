package com.wynk.codecontext.port.contract.graph;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class ContractRelationshipEvidenceTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void demotesConfidenceAndMarksAmbiguousWithoutDuplicatingMarker() {
        var evidence = mapper.createArrayNode().add(mapper.createObjectNode().put("match", "exact"));
        var marked = ContractRelationshipEvidence.markAmbiguous(evidence, 3);
        assertTrue(ContractRelationshipEvidence.isAmbiguous(marked));
        assertEquals(0.40d, ContractRelationshipEvidence.demotedConfidence(0.95d));
        var again = ContractRelationshipEvidence.markAmbiguous(marked, 4);
        long markers = 0;
        for (var elem : again) if ("AMBIGUOUS".equals(elem.path("relationshipStatus").asText())) markers++;
        assertEquals(1, markers);
        assertEquals(4, again.get(again.size() - 1).path("providerCount").asInt());
    }

    @Test void clearsAmbiguousAndRecordsResolvedWhenSingleProviderRemains() {
        var ambiguous = ContractRelationshipEvidence.markAmbiguous(mapper.createArrayNode(), 2);
        var resolved = ContractRelationshipEvidence.clearAmbiguousAndResolve(ambiguous);
        assertFalse(ContractRelationshipEvidence.isAmbiguous(resolved));
        assertEquals("RESOLVED", resolved.get(resolved.size() - 1).path("relationshipStatus").asText());
    }
}
