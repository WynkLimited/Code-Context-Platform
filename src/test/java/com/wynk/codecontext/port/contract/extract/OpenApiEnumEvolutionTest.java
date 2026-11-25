package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.RepositoryContext;
import com.wynk.codecontext.port.contract.ScalarEnumDomain;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.Compatibility;
import com.wynk.codecontext.port.contract.compat.ContractCompatibilityEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class OpenApiEnumEvolutionTest {
    @TempDir Path root;
    private final ContractCompatibilityEngine engine = new ContractCompatibilityEngine();
    private static String strings(String values) { return "{\"type\":\"string\",\"enum\":" + values + "}"; }

    @Test void requestNarrowingBreaksWhileWideningAcceptsExistingValues() throws Exception {
        var before = export(strings("[\"A\",\"B\"]"), false);
        var after = export(strings("[\"A\"]"), false);
        verdict(before, after, Compatibility.BREAKING);
        verdict(after, before, Compatibility.BACKWARD_COMPATIBLE);
    }
    @Test void responseWideningBreaksWhileNarrowingPreservesOldReaders() throws Exception {
        var before = export(strings("[\"A\"]"), true);
        var after = export(strings("[\"A\",\"B\"]"), true);
        verdict(before, after, Compatibility.BREAKING);
        verdict(after, before, Compatibility.BACKWARD_COMPATIBLE);
    }
    @Test void orderDuplicatesAndNumericSpellingDoNotInventChanges() throws Exception {
        var a = export(strings("[\"B\",\"A\",\"A\"]"), false);
        var b = export(strings("[\"A\",\"B\"]"), false);
        assertEquals(a.contentHash(), b.contentHash());
        assertTrue(engine.compare(List.of(a), List.of(b)).isEmpty());
        var number = export("{\"type\":\"number\",\"enum\":[1,2.0]}", true);
        var decimal = export("{\"type\":\"number\",\"enum\":[2,1.0]}", true);
        assertEquals(number.contentHash(), decimal.contentHash());
    }
    @Test void punctuationAndUnicodeCannotSplitPersistedSchemaFields() throws Exception {
        var values = Set.of("\"a,b:c~.\"", "\"λ\"", "\"a\\\"b\"");
        String token = ScalarEnumDomain.encode("string", values);
        assertFalse(token.contains(",")); assertFalse(token.contains(":"));
        assertEquals(values, ScalarEnumDomain.parse(token).values());
        verdict(export(strings("[\"a,b:c~.\",\"λ\"]"), false),
                export(strings("[\"λ\"]"), false), Compatibility.BREAKING);
    }
    @Test void unrestrictedTransitionsUseTheSameDirectionalRules() throws Exception {
        var finiteRequest = export(strings("[\"A\"]"), false);
        var anyRequest = export("{\"type\":\"string\"}", false);
        verdict(finiteRequest, anyRequest, Compatibility.BACKWARD_COMPATIBLE);
        verdict(anyRequest, finiteRequest, Compatibility.BREAKING);
        var finiteResponse = export(strings("[\"A\"]"), true);
        var anyResponse = export("{\"type\":\"string\"}", true);
        verdict(finiteResponse, anyResponse, Compatibility.BREAKING);
        verdict(anyResponse, finiteResponse, Compatibility.BACKWARD_COMPATIBLE);
    }
    @Test void unsupportedNullMixedAndObjectDomainsStayUnknownWithChangedIdentity() throws Exception {
        for (String domain : List.of("{\"type\":\"string\",\"enum\":[\"A\",null]}",
                "{\"enum\":[1,\"A\"]}", "{\"type\":\"object\",\"enum\":[{\"x\":1}]}")) {
            var unsupported = export(domain, true);
            assertEquals(false, unsupported.metadata().get("schemaComplete"));
            verdict(unsupported, export(strings("[\"A\"]"), true), Compatibility.UNKNOWN);
        }
    }
    @Test void additionalScalarValidationCannotBeMistakenForAnUnrestrictedDomain() throws Exception {
        var finite = export(strings("[\"A\"]"), false);
        var constrained = export("{\"type\":\"string\",\"minLength\":10}", false);
        assertEquals(false, constrained.metadata().get("schemaComplete"));
        verdict(finite, constrained, Compatibility.UNKNOWN);
        verdict(constrained, finite, Compatibility.UNKNOWN);
        var constrainedEnum = export("{\"type\":\"string\",\"enum\":[\"A\"],\"pattern\":\"B+\"}", false);
        assertEquals(false, constrainedEnum.metadata().get("schemaComplete"));
        verdict(finite, constrainedEnum, Compatibility.UNKNOWN);
    }
    @Test void compatibleEnumMovementCannotHideBreakingCardinalityChanges() throws Exception {
        verdict(export(strings("[\"A\"]"), false, false), export(strings("[\"A\",\"B\"]"), false, true), Compatibility.BREAKING);
        verdict(export(strings("[\"A\",\"B\"]"), true, true), export(strings("[\"A\"]"), true, false), Compatibility.BREAKING);
        verdict(export("{\"type\":\"string\"}", true, true), export("{\"type\":\"string\"}", true, false), Compatibility.BREAKING);
    }
    @Test void nestedArrayEnumMovementIsExplicitlyUnknownUntilModelled() throws Exception {
        verdict(export("{\"type\":\"array\",\"items\":" + strings("[\"A\"]") + "}", false),
                export("{\"type\":\"array\",\"items\":" + strings("[\"A\",\"B\"]") + "}", false), Compatibility.UNKNOWN);
        var oversized = export(strings("[\"" + "a".repeat(17000) + "\"]"), false);
        assertEquals(false, oversized.metadata().get("schemaComplete"));
    }
    private void verdict(ContractExport before, ContractExport after, Compatibility expected) {
        var changes = engine.compare(List.of(before), List.of(after));
        assertEquals(1, changes.size(), changes.toString());
        assertEquals(expected, changes.getFirst().compatibility(), changes.toString());
    }
    private ContractExport export(String schema, boolean response) throws Exception {
        return export(schema, response, false);
    }
    private ContractExport export(String schema, boolean response, boolean required) throws Exception {
        Path directory = Files.createTempDirectory(root, "snapshot");
        String operation = response
                ? "{\"responses\":{\"200\":{\"content\":{\"application/json\":{\"schema\":{\"type\":\"object\",\"required\":" + (required ? "[\"status\"]" : "[]") + ",\"properties\":{\"status\":" + schema + "}}}}}}}"
                : "{\"parameters\":[{\"name\":\"status\",\"in\":\"query\",\"required\":" + required + ",\"schema\":" + schema + "}]}";
        Files.writeString(directory.resolve("openapi.json"), "{\"openapi\":\"3.0.3\",\"paths\":{\"/enum\":{\"get\":" + operation + "}}}");
        return new RestContractExtractor(JsonMapper.builder().build()).extractExports(
                new RepositoryContext("fixture", "provider", "a".repeat(40), "main", directory)).getFirst();
    }
}
