package com.wynk.codecontext.port.contract.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
