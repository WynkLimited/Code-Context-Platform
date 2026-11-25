package com.wynk.codecontext.port.contract.graph;

import com.wynk.codecontext.port.contract.source.KotlinAbiSurface;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossRepoImpactResolverTest {

    @Test
    void identifiesDependencyAtProviderBaselineAsNotProvenBroken() {
        assertEquals("CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN",
                CrossRepoImpactResolver.currentCompatibility("1.0", "1.0", "2.0"));
    }

    @Test
    void identifiesDependencyAtProviderHead() {
        assertEquals("DEPENDENCY_VERSION_MATCHES_PROVIDER_HEAD",
                CrossRepoImpactResolver.currentCompatibility("2.0", "1.0", "2.0"));
    }

    @Test
    void identifiesDependencyAtOtherReleaseAsStillPinnedElsewhere() {
        assertEquals("DEPENDENCY_REMAINS_PINNED_TO_OLDER_OR_DIFFERENT_RELEASE",
                CrossRepoImpactResolver.currentCompatibility("0.9", "1.0", "2.0"));
    }

    @Test
    void identifiesMissingDependencyVersionAsUnknown() {
        assertEquals("DEPENDENCY_VERSION_UNKNOWN",
                CrossRepoImpactResolver.currentCompatibility(null, "1.0", "2.0"));
    }

    @Test
    void kotlinAbiRemovalsProduceSemanticKeysConsumersCanMatchWithoutClaimingSourceSafety() {
        String before = "API\nKOTLIN_BINARY org.acme.Money#convert(Ljava/lang/String;)Ljava/lang/String;\n";
        String after = "API\nKOTLIN_BINARY org.acme.Money#convert(Ljava/math/BigDecimal;)Ljava/lang/String;\n";
        var removed = KotlinAbiSurface.removedSemanticKeys(before, after);
        assertEquals(1, removed.size());
        assertEquals("org.acme.Money#convert(java.lang.String)", removed.get(0));
        // Consumers pinned to the baseline remain CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN until they upgrade.
        assertEquals("CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN",
                CrossRepoImpactResolver.currentCompatibility("1.0", "1.0", "2.0"));
        assertTrue(KotlinAbiSurface.removedBinaryKeys(before, after).get(0).contains("Ljava/lang/String;"));
    }
}
