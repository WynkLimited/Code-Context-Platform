package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractIndexHashTest {

    private static ContractExport export(String pkg, String name, String hash, String file) {
        return new ContractExport(ContractIdentity.ofProto(pkg, name), ContractType.PROTO,
                file, pkg + "." + name, hash, Map.of());
    }

    private static ContractImport imported(String pkg, String name, String version) {
        return new ContractImport(ContractIdentity.ofProto(pkg, name), ContractType.PROTO,
                "src/Consumer.java", pkg + "." + name, version, Map.of());
    }

    private static ContractIndex index(List<ContractExport> exports, List<ContractImport> imports) {
        return index(exports, imports, Map.of("proto", "1.0.0"),
                ContractModels.INDEX_SCHEMA_VERSION, ContractModels.COMPATIBILITY_RULES_VERSION);
    }

    private static ContractIndex index(
            List<ContractExport> exports,
            List<ContractImport> imports,
            Map<String, String> extractorVersions,
            String schemaVersion,
            String rulesVersion
    ) {
        return new ContractIndex("WynkLimited", "User-Growth", "a".repeat(40), "preprod",
                schemaVersion, extractorVersions, rulesVersion, ContractModels.GENERATOR_VERSION,
                Instant.parse("2026-01-01T00:00:00Z"), null, exports, imports);
    }

    private static final ContractExport CAMPAIGN = export("in.wynk", "Campaign", "sha256:aaa", "a/Campaign.proto");
    private static final ContractExport REALM = export("in.wynk", "Realm", "sha256:bbb", "a/Campaign.proto");

    @Test
    void sameContractsProduceSameHash() {
        assertEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN, REALM), List.of())),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN, REALM), List.of())));
    }

    @Test
    void reorderedRecordsProduceSameHash() {
        assertEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN, REALM), List.of())),
                ContractIndexHasher.hash(index(List.of(REALM, CAMPAIGN), List.of())));
    }

    @Test
    void commitAndTimestampDoNotAffectHash() {
        var base = index(List.of(CAMPAIGN), List.of());
        var laterCommit = new ContractIndex(base.owner(), base.repository(), "b".repeat(40), base.branch(),
                base.indexSchemaVersion(), base.extractorVersions(), base.compatibilityRulesVersion(),
                base.generatorVersion(), Instant.parse("2026-06-01T12:00:00Z"), null,
                base.exports(), base.imports());

        assertEquals(ContractIndexHasher.hash(base), ContractIndexHasher.hash(laterCommit),
                "a README-only commit must not invalidate a consumer's cached verdict");
    }

    @Test
    void movingAnUnchangedContractToANewFileDoesNotAffectHash() {
        assertEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of())),
                ContractIndexHasher.hash(index(
                        List.of(export("in.wynk", "Campaign", "sha256:aaa", "moved/Elsewhere.proto")),
                        List.of())),
                "relocating an identical contract is not a contract change");
    }

    @Test
    void addingAnIdenticalVendoredCopyDoesNotAffectHash() {
        var copy = export("in.wynk", "Campaign", "sha256:aaa", "second/Campaign.proto");

        assertEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of())),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN, copy), List.of())));
    }

    @Test
    void aDivergingVendoredCopyChangesTheHash() {
        var drifted = export("in.wynk", "Campaign", "sha256:DIFFERENT", "second/Campaign.proto");

        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of())),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN, drifted), List.of())));
    }

    @Test
    void contractContentChangeChangesTheHash() {
        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of())),
                ContractIndexHasher.hash(index(
                        List.of(export("in.wynk", "Campaign", "sha256:CHANGED", "a/Campaign.proto")), List.of())));
    }

    @Test
    void addingAndRemovingContractsChangesTheHash() {
        String one = ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of()));
        String two = ContractIndexHasher.hash(index(List.of(CAMPAIGN, REALM), List.of()));

        assertNotEquals(one, two);
        assertEquals(one, ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of())));
    }

    @Test
    void importsParticipateInTheHash() {
        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of())),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(imported("in.wynk", "Other", "2.3.0")))));
    }

    @Test
    void dependencyVersionChangeOnAnImportChangesTheHash() {
        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(), List.of(imported("in.wynk", "Other", "2.3.0")))),
                ContractIndexHasher.hash(index(List.of(), List.of(imported("in.wynk", "Other", "2.4.0")))));
    }

    @Test
    void extractorVersionChangeChangesTheHash() {
        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(), Map.of("proto", "1.0.0"),
                        ContractModels.INDEX_SCHEMA_VERSION, ContractModels.COMPATIBILITY_RULES_VERSION)),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(), Map.of("proto", "1.1.0"),
                        ContractModels.INDEX_SCHEMA_VERSION, ContractModels.COMPATIBILITY_RULES_VERSION)));
    }

    @Test
    void addingAnExtractorChangesTheHash() {
        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(), Map.of("proto", "1.0.0"),
                        ContractModels.INDEX_SCHEMA_VERSION, ContractModels.COMPATIBILITY_RULES_VERSION)),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(),
                        Map.of("proto", "1.0.0", "grpc", "1.0.0"),
                        ContractModels.INDEX_SCHEMA_VERSION, ContractModels.COMPATIBILITY_RULES_VERSION)));
    }

    @Test
    void schemaVersionChangeChangesTheHash() {
        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(), Map.of("proto", "1.0.0"),
                        "1.0.0", ContractModels.COMPATIBILITY_RULES_VERSION)),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(), Map.of("proto", "1.0.0"),
                        "2.0.0", ContractModels.COMPATIBILITY_RULES_VERSION)));
    }

    @Test
    void compatibilityRulesVersionChangeChangesTheHash() {
        assertNotEquals(
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(), Map.of("proto", "1.0.0"),
                        ContractModels.INDEX_SCHEMA_VERSION, "1.0.0")),
                ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of(), Map.of("proto", "1.0.0"),
                        ContractModels.INDEX_SCHEMA_VERSION, "2.0.0")),
                "a rules correction must re-open every cached analysis");
    }

    @Test
    void repositoryIdentityParticipatesInTheHash() {
        var base = index(List.of(CAMPAIGN), List.of());
        var otherRepo = new ContractIndex("WynkLimited", "content-discovery", base.commit(), base.branch(),
                base.indexSchemaVersion(), base.extractorVersions(), base.compatibilityRulesVersion(),
                base.generatorVersion(), base.generatedAt(), null, base.exports(), base.imports());

        assertNotEquals(ContractIndexHasher.hash(base), ContractIndexHasher.hash(otherRepo));
    }

    @Test
    void repositoryIdentityIsCaseInsensitive() {
        var base = index(List.of(CAMPAIGN), List.of());
        var differentCase = new ContractIndex("wynklimited", "user-growth", base.commit(), base.branch(),
                base.indexSchemaVersion(), base.extractorVersions(), base.compatibilityRulesVersion(),
                base.generatorVersion(), base.generatedAt(), null, base.exports(), base.imports());

        assertEquals(ContractIndexHasher.hash(base), ContractIndexHasher.hash(differentCase));
    }

    @Test
    void hashIsPrefixedSha256() {
        String hash = ContractIndexHasher.hash(index(List.of(CAMPAIGN), List.of()));

        assertTrue(hash.startsWith("sha256:"), hash);
        assertEquals("sha256:".length() + 64, hash.length());
    }
}
