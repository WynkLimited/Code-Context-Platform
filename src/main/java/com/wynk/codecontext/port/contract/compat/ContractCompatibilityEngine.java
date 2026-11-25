package com.wynk.codecontext.port.contract.compat;

import com.wynk.codecontext.port.contract.ContractModels;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.Compatibility;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChange;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChangeType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic protobuf/gRPC compatibility rules.
 *
 * <p>A content-hash change proves only that a contract changed — never that a consumer broke.
 * This engine turns "changed" into a specific, classified verdict, and it is the <em>only</em>
 * component permitted to produce {@link Compatibility#BREAKING}. The LLM never makes this call.
 *
 * <p>The rules are documented in {@code docs/CONTRACT-COMPATIBILITY-RULES.md}. Changing any verdict
 * requires bumping {@link ContractModels#COMPATIBILITY_RULES_VERSION}, which invalidates every
 * cached index and analysis rather than leaving stale conclusions in place.
 */

public class ContractCompatibilityEngine {

    public String rulesVersion() {
        return ContractModels.COMPATIBILITY_RULES_VERSION;
    }

    /**
     * Compares a repository's exported contracts between two commits.
     *
     * @param before exports at the PR base commit
     * @param after  exports at the PR head commit
     * @return every classified change, ordered by contract key then change type
     */
    public List<ContractChange> compare(List<ContractExport> before, List<ContractExport> after) {
        Map<String, ContractExport> baseline = canonicalize(before);
        Map<String, ContractExport> head = canonicalize(after);

        var changes = new ArrayList<ContractChange>();
        var removedKeys = new LinkedHashSet<>(baseline.keySet());
        removedKeys.removeAll(head.keySet());
        var addedKeys = new LinkedHashSet<>(head.keySet());
        addedKeys.removeAll(baseline.keySet());

        // Same key on both sides: diff the definitions.
        for (var entry : baseline.entrySet()) {
            ContractExport headExport = head.get(entry.getKey());
            if (headExport != null) {
                changes.addAll(compareSame(entry.getValue(), headExport));
                if (headExport.type() == ContractModels.ContractType.MAVEN
                        && !java.util.Objects.equals(entry.getValue().metadata().get("mavenBuildContract"), headExport.metadata().get("mavenBuildContract"))) {
                    changes.add(change(entry.getValue(), headExport, ContractChangeType.BUILD_DEPENDENCY_CHANGED, Compatibility.UNKNOWN,
                            String.valueOf(entry.getValue().metadata().get("mavenBuildContract")), String.valueOf(headExport.metadata().get("mavenBuildContract")),
                            "Maven inheritance or dependency management changed; effective model/classpath evidence is required, not an assumed compile break."));
                }
            }
        }

        var consumedAdds = new LinkedHashSet<String>();
        var consumedRemovals = new LinkedHashSet<String>();

        // A removal plus an addition with an identical body is a rename, not an unrelated pair.
        for (String removedKey : removedKeys) {
            ContractExport removed = baseline.get(removedKey);
            if (removed.type() != ContractModels.ContractType.PROTO
                    && removed.type() != ContractModels.ContractType.GRPC) {
                continue;
            }
            Optional<String> renamedTo = addedKeys.stream()
                    .filter(k -> !consumedAdds.contains(k))
                    .filter(k -> sameBody(removed, head.get(k)))
                    .min(Comparator.naturalOrder());
            if (renamedTo.isEmpty()) {
                continue;
            }
            ContractExport added = head.get(renamedTo.get());
            consumedAdds.add(renamedTo.get());
            consumedRemovals.add(removedKey);
            changes.add(renameChange(removed, added));
        }

        for (String removedKey : removedKeys) {
            if (consumedRemovals.contains(removedKey)) {
                continue;
            }
            ContractExport removed = baseline.get(removedKey);
            changes.add(new ContractChange(
                    removed.contract(),
                    removed.type() == ContractModels.ContractType.GRPC
                            ? ContractChangeType.RPC_REMOVED
                            : ContractChangeType.CONTRACT_REMOVED,
                    removalCompatibility(removed.type()),
                    removed.contentHash(), null,
                    signatureOf(removed), null,
                    rulesVersion(),
                    "Contract " + removedKey + " no longer exists; consumers referencing it break."));
        }

        for (String addedKey : addedKeys) {
            if (consumedAdds.contains(addedKey)) {
                continue;
            }
            ContractExport added = head.get(addedKey);
            changes.add(new ContractChange(
                    added.contract(),
                    added.type() == ContractModels.ContractType.GRPC
                            ? ContractChangeType.RPC_ADDED
                            : ContractChangeType.CONTRACT_ADDED,
                    additionCompatibility(added.type()),
                    null, added.contentHash(),
                    null, signatureOf(added),
                    rulesVersion(),
                    "Contract " + addedKey + " is new; existing consumers are unaffected."));
        }

        changes.sort(Comparator
                .comparing(ContractChange::contractKey)
                .thenComparing(c -> c.changeType().name()));
        return List.copyOf(changes);
    }

    private ContractChange renameChange(ContractExport removed, ContractExport added) {
        boolean packageChanged = !java.util.Objects.equals(
                removed.contract().namespace(), added.contract().namespace());
        boolean isRpc = added.type() == ContractModels.ContractType.GRPC;

        ContractChangeType type;
        if (isRpc) {
            type = ContractChangeType.SERVICE_RENAMED;
        } else if (packageChanged) {
            type = ContractChangeType.PACKAGE_CHANGED;
        } else {
            type = ContractChangeType.MESSAGE_RENAMED;
        }

        return new ContractChange(
                removed.contract(), type, Compatibility.BREAKING,
                removed.contentHash(), added.contentHash(),
                signatureOf(removed), signatureOf(added),
                rulesVersion(),
                removed.canonicalKey() + " was renamed to " + added.canonicalKey()
                        + "; consumers referencing the old name break.");
    }

    /** Diffs two definitions of the same contract key. */
    private List<ContractChange> compareSame(ContractExport before, ContractExport after) {
        String beforeSignature = signatureOf(before);
        String afterSignature = signatureOf(after);
        if (beforeSignature == null || afterSignature == null) {
            return List.of();
        }
        if(before.type()==ContractModels.ContractType.MAVEN &&
                (Boolean.FALSE.equals(before.metadata().get("kotlinMetadataComplete")) || Boolean.FALSE.equals(after.metadata().get("kotlinMetadataComplete"))))
            return List.of(change(before,after,ContractChangeType.API_SIGNATURE_CHANGED,Compatibility.UNKNOWN,
                    beforeSignature,afterSignature,"Kotlin compiler or metadata extraction incomplete; no ABI safety conclusion."));
        if (beforeSignature.equals(afterSignature)) {
            if (before.type() == ContractModels.ContractType.MAVEN
                    && !java.util.Objects.equals(before.metadata().get("javaApiAnnotations"), after.metadata().get("javaApiAnnotations"))) {
                return List.of(change(before, after, ContractChangeType.API_SIGNATURE_CHANGED, Compatibility.UNKNOWN,
                        beforeSignature, afterSignature, "Java callable signatures are unchanged; annotation contract requires technology-specific review. Before: "
                                + before.metadata().get("javaApiAnnotations") + "; after: " + after.metadata().get("javaApiAnnotations")));
            }
            return List.of();
        }
        if (ProtoSignatureParser.isMessage(beforeSignature) && ProtoSignatureParser.isMessage(afterSignature)) {
            return compareMessages(before, after, beforeSignature, afterSignature);
        }
        if (ProtoSignatureParser.isEnum(beforeSignature) && ProtoSignatureParser.isEnum(afterSignature)) {
            return compareEnums(before, after, beforeSignature, afterSignature);
        }
        if (ProtoSignatureParser.isRpc(beforeSignature) && ProtoSignatureParser.isRpc(afterSignature)) {
            return compareRpcs(before, after, beforeSignature, afterSignature);
        }
        if (before.type() == ContractModels.ContractType.MAVEN) {
            if (Boolean.FALSE.equals(before.metadata().get("javaApiExtractionComplete"))
                    || Boolean.FALSE.equals(after.metadata().get("javaApiExtractionComplete"))) {
                return List.of(change(before, after, ContractChangeType.API_SIGNATURE_CHANGED, Compatibility.UNKNOWN,
                        beforeSignature, afterSignature, "Java declaration extraction incomplete; API compatibility is unresolved."));
            }
            Set<String> afterApi = new java.util.HashSet<>(java.util.Arrays.asList(afterSignature.split("\\R")));
            boolean removedApi = java.util.Arrays.stream(beforeSignature.split("\\R"))
                    .filter(line -> !line.isBlank())
                    .anyMatch(line -> !afterApi.contains(line));
            return List.of(change(before, after, ContractChangeType.API_SIGNATURE_CHANGED,
                    removedApi ? Compatibility.BREAKING : Compatibility.BACKWARD_COMPATIBLE,
                    beforeSignature, afterSignature,
                    removedApi ? "Published Maven public API removed or changed. JVM descriptor removals are binary incompatible; Kotlin source compatibility remains unknown."
                            : "Published Maven public API was extended compatibly."));
        }
        if (before.type() == ContractModels.ContractType.KAFKA) {
            if (ProtoSignatureParser.isMessage(beforeSignature) && ProtoSignatureParser.isMessage(afterSignature)) {
                return compareMessages(before, after, beforeSignature, afterSignature);
            }
            if (StructuredSchemaCompat.isFieldSchema(beforeSignature)
                    && StructuredSchemaCompat.isFieldSchema(afterSignature)) {
                // Kafka Avro/JSON Schema: removals, type changes and required additions break readers.
                var diffs = StructuredSchemaCompat.compareKafkaFields(beforeSignature, afterSignature);
                if (!diffs.isEmpty()) {
                    return diffs.stream()
                            .map(diff -> change(before, after, diff.changeType(), diff.compatibility(),
                                    beforeSignature, afterSignature, diff.detail()))
                            .toList();
                }
            }
            boolean declaredSchemas = hasMetadata(before, "schema") && hasMetadata(after, "schema");
            return List.of(change(before, after, ContractChangeType.SCHEMA_CHANGED,
                    Compatibility.UNKNOWN,
                    beforeSignature, afterSignature,
                    declaredSchemas
                            ? "Declared Kafka schema changed; schema-specific compatibility is not proven."
                            : "Kafka payload changed without a declared schema; impact is inferred."));
        }
        if (before.type() == ContractModels.ContractType.REST) {
            if (StructuredSchemaCompat.isRestOperation(beforeSignature)
                    && StructuredSchemaCompat.isRestOperation(afterSignature)) {
                var diffs = StructuredSchemaCompat.compareRest(beforeSignature, afterSignature);
                if (!diffs.isEmpty()) {
                    return diffs.stream()
                            .map(diff -> change(before, after, diff.changeType(), diff.compatibility(),
                                    beforeSignature, afterSignature, diff.detail()))
                            .toList();
                }
                return List.of();
            }
            boolean openApi = "openapi".equals(before.metadata().get("framework"))
                    && "openapi".equals(after.metadata().get("framework"));
            return List.of(change(before, after, ContractChangeType.ENDPOINT_SIGNATURE_CHANGED,
                    Compatibility.UNKNOWN,
                    beforeSignature, afterSignature,
                    openApi
                            ? "Declared OpenAPI operation changed; semantic compatibility requires validation."
                            : "Annotation-derived REST signature changed; impact requires validation."));
        }
        return List.of(unknown(before, after, beforeSignature, afterSignature,
                "Contract changed in a way the current rules do not model."));
    }

    private static Compatibility removalCompatibility(ContractModels.ContractType type) {
        return switch (type) {
            case PROTO, GRPC, MAVEN, REST -> Compatibility.BREAKING;
            case KAFKA, ENUM, CONFIG, CACHE, DATABASE, STRING_FORMAT -> Compatibility.UNKNOWN;
        };
    }

    private static Compatibility additionCompatibility(ContractModels.ContractType type) {
        return switch (type) {
            case PROTO, GRPC, MAVEN, KAFKA, REST -> Compatibility.BACKWARD_COMPATIBLE;
            case ENUM, CONFIG, CACHE, DATABASE, STRING_FORMAT -> Compatibility.UNKNOWN;
        };
    }

    private static boolean hasMetadata(ContractExport export, String key) {
        Object value = export.metadata().get(key);
        return value != null && !String.valueOf(value).isBlank();
    }

    private List<ContractChange> compareMessages(
            ContractExport before, ContractExport after, String beforeSignature, String afterSignature) {
        var beforeMsg = ProtoSignatureParser.parseMessage(beforeSignature);
        var afterMsg = ProtoSignatureParser.parseMessage(afterSignature);
        var changes = new ArrayList<ContractChange>();

        var allNumbers = new TreeSet<Integer>();
        allNumbers.addAll(beforeMsg.fieldsByNumber().keySet());
        allNumbers.addAll(afterMsg.fieldsByNumber().keySet());

        // Detect renumbering before treating numbers as independent add/remove pairs.
        Set<Integer> renumberedFrom = new LinkedHashSet<>();
        Set<Integer> renumberedTo = new LinkedHashSet<>();
        for (var beforeEntry : beforeMsg.fieldsByNumber().entrySet()) {
            if (afterMsg.fieldsByNumber().containsKey(beforeEntry.getKey())) {
                continue;
            }
            var beforeField = beforeEntry.getValue();
            afterMsg.fieldsByNumber().entrySet().stream()
                    .filter(e -> !beforeMsg.fieldsByNumber().containsKey(e.getKey()))
                    .filter(e -> !renumberedTo.contains(e.getKey()))
                    .filter(e -> e.getValue().name().equals(beforeField.name()))
                    .findFirst()
                    .ifPresent(e -> {
                        renumberedFrom.add(beforeEntry.getKey());
                        renumberedTo.add(e.getKey());
                        changes.add(change(before, after, ContractChangeType.FIELD_NUMBER_CHANGED,
                                Compatibility.BREAKING, beforeSignature, afterSignature,
                                "Field '" + beforeField.name() + "' moved from number "
                                        + beforeEntry.getKey() + " to " + e.getKey()
                                        + "; previously encoded data decodes into the wrong field."));
                    });
        }

        for (Integer number : allNumbers) {
            var beforeField = beforeMsg.fieldsByNumber().get(number);
            var afterField = afterMsg.fieldsByNumber().get(number);

            if (beforeField == null && afterField != null) {
                if (renumberedTo.contains(number)) {
                    continue;
                }
                changes.add(change(before, after, ContractChangeType.FIELD_ADDED,
                        "LABEL_REQUIRED".equals(afterField.label()) ? Compatibility.BREAKING : Compatibility.BACKWARD_COMPATIBLE, beforeSignature, afterSignature,
                        "Field '" + afterField.name() + "' (" + number
                                + (") added; " + ("LABEL_REQUIRED".equals(afterField.label()) ? "old messages lacking this field fail new-reader initialization." : "existing consumers ignore unknown fields."))));
            } else if (beforeField != null && afterField == null) {
                if (renumberedFrom.contains(number)) {
                    continue;
                }
                changes.add(change(before, after, ContractChangeType.FIELD_REMOVED,
                        Compatibility.BREAKING, beforeSignature, afterSignature,
                        "Field '" + beforeField.name() + "' (" + number
                                + ") removed; consumers reading it lose data and generated"
                                + " accessors disappear."));
            } else if (beforeField != null) {
                changes.addAll(compareField(before, after, beforeSignature, afterSignature,
                        number, beforeField, afterField));
            }
        }
        return changes;
    }

    private List<ContractChange> compareField(
            ContractExport before,
            ContractExport after,
            String beforeSignature,
            String afterSignature,
            int number,
            ProtoSignatureParser.Field beforeField,
            ProtoSignatureParser.Field afterField
    ) {
        var changes = new ArrayList<ContractChange>();
        if (!beforeField.effectiveType().equals(afterField.effectiveType())) {
            changes.add(change(before, after, ContractChangeType.FIELD_TYPE_CHANGED,
                    Compatibility.BREAKING, beforeSignature, afterSignature,
                    "Field '" + beforeField.name() + "' (" + number + ") changed type from "
                            + beforeField.effectiveType() + " to " + afterField.effectiveType()
                            + "; the generated accessor signature changes even where the wire"
                            + " encoding happens to overlap."));
        }
        if (!beforeField.label().equals(afterField.label())) {
            changes.add(change(before, after, ContractChangeType.FIELD_CARDINALITY_CHANGED,
                    Compatibility.BREAKING, beforeSignature, afterSignature,
                    "Field '" + beforeField.name() + "' (" + number + ") changed cardinality from "
                            + beforeField.label() + " to " + afterField.label() + "."));
        }
        if (!java.util.Objects.equals(beforeField.oneof(), afterField.oneof())) {
            changes.add(change(before, after, ContractChangeType.FIELD_CARDINALITY_CHANGED,
                    Compatibility.BREAKING, beforeSignature, afterSignature,
                    "Field '" + beforeField.name() + "' (" + number + ") changed oneof membership from "
                            + describeOneof(beforeField.oneof()) + " to "
                            + describeOneof(afterField.oneof()) + "."));
        }
        if (!beforeField.name().equals(afterField.name())
                && beforeField.effectiveType().equals(afterField.effectiveType())) {
            changes.add(change(before, after, ContractChangeType.FIELD_TYPE_CHANGED,
                    Compatibility.BREAKING, beforeSignature, afterSignature,
                    "Field " + number + " renamed from '" + beforeField.name() + "' to '"
                            + afterField.name() + "'; the wire stays compatible but generated"
                            + " accessors and JSON field names change."));
        }
        return changes;
    }

    private List<ContractChange> compareEnums(
            ContractExport before, ContractExport after, String beforeSignature, String afterSignature) {
        var beforeEnum = ProtoSignatureParser.parseEnum(beforeSignature);
        var afterEnum = ProtoSignatureParser.parseEnum(afterSignature);
        var changes = new ArrayList<ContractChange>();

        var numbers = new TreeSet<Integer>();
        numbers.addAll(beforeEnum.valuesByNumber().keySet());
        numbers.addAll(afterEnum.valuesByNumber().keySet());

        for (Integer number : numbers) {
            String beforeValue = beforeEnum.valuesByNumber().get(number);
            String afterValue = afterEnum.valuesByNumber().get(number);
            if (beforeValue == null) {
                changes.add(change(before, after, ContractChangeType.ENUM_VALUE_ADDED,
                        Compatibility.BACKWARD_COMPATIBLE, beforeSignature, afterSignature,
                        "Enum value '" + afterValue + "' (" + number + ") added; consumers that"
                                + " switch exhaustively may not handle it."));
            } else if (afterValue == null) {
                changes.add(change(before, after, ContractChangeType.ENUM_VALUE_REMOVED,
                        Compatibility.BREAKING, beforeSignature, afterSignature,
                        "Enum value '" + beforeValue + "' (" + number + ") removed; consumers"
                                + " referencing it break."));
            } else if (!beforeValue.equals(afterValue)) {
                changes.add(change(before, after, ContractChangeType.ENUM_VALUE_REMOVED,
                        Compatibility.BREAKING, beforeSignature, afterSignature,
                        "Enum value " + number + " renamed from '" + beforeValue + "' to '"
                                + afterValue + "'; consumers referencing the old name break."));
            }
        }
        return changes;
    }

    private List<ContractChange> compareRpcs(
            ContractExport before, ContractExport after, String beforeSignature, String afterSignature) {
        var beforeRpc = ProtoSignatureParser.parseRpc(beforeSignature);
        var afterRpc = ProtoSignatureParser.parseRpc(afterSignature);
        var changes = new ArrayList<ContractChange>();

        if (!beforeRpc.requestType().equals(afterRpc.requestType())) {
            changes.add(change(before, after, ContractChangeType.RPC_REQUEST_CHANGED,
                    Compatibility.BREAKING, beforeSignature, afterSignature,
                    "RPC request type changed from " + beforeRpc.requestType()
                            + " to " + afterRpc.requestType() + "; callers break."));
        }
        if (!beforeRpc.responseType().equals(afterRpc.responseType())) {
            changes.add(change(before, after, ContractChangeType.RPC_RESPONSE_CHANGED,
                    Compatibility.BREAKING, beforeSignature, afterSignature,
                    "RPC response type changed from " + beforeRpc.responseType()
                            + " to " + afterRpc.responseType() + "; callers break."));
        }
        if (beforeRpc.clientStreaming() != afterRpc.clientStreaming()
                || beforeRpc.serverStreaming() != afterRpc.serverStreaming()) {
            changes.add(change(before, after, ContractChangeType.RPC_REQUEST_CHANGED,
                    Compatibility.BREAKING, beforeSignature, afterSignature,
                    "RPC streaming cardinality changed (clientStreaming "
                            + beforeRpc.clientStreaming() + "→" + afterRpc.clientStreaming()
                            + ", serverStreaming " + beforeRpc.serverStreaming() + "→"
                            + afterRpc.serverStreaming() + "); callers break."));
        }
        return changes;
    }

    private ContractChange change(
            ContractExport before,
            ContractExport after,
            ContractChangeType type,
            Compatibility compatibility,
            String beforeSignature,
            String afterSignature,
            String detail
    ) {
        return new ContractChange(
                after != null ? after.contract() : before.contract(),
                type, compatibility,
                before == null ? null : before.contentHash(),
                after == null ? null : after.contentHash(),
                beforeSignature, afterSignature, rulesVersion(), detail);
    }

    private ContractChange unknown(
            ContractExport before, ContractExport after, String beforeSignature, String afterSignature, String detail) {
        return change(before, after, ContractChangeType.CONTRACT_ADDED, Compatibility.UNKNOWN,
                beforeSignature, afterSignature, detail);
    }

    /**
     * Collapses a repository's exports to one definition per contract key.
     *
     * <p>Vendored copies mean the same key can appear many times. Baseline-vs-head comparison needs
     * one definition per side, so the lexicographically first source file wins — deterministically,
     * so the same input always yields the same verdict. Divergence <em>between</em> copies is a
     * separate signal, surfaced by the contract graph rather than here.
     */
    private static Map<String, ContractExport> canonicalize(List<ContractExport> exports) {
        var out = new LinkedHashMap<String, ContractExport>();
        exports.stream()
                .sorted(Comparator.comparing((ContractExport e) -> e.sourceFile() == null ? "" : e.sourceFile()))
                .forEach(e -> out.putIfAbsent(e.canonicalKey(), e));
        return out;
    }

    private static boolean sameBody(ContractExport a, ContractExport b) {
        if (a == null || b == null || a.type() != b.type()) {
            return false;
        }
        String left = stripName(signatureOf(a));
        String right = stripName(signatureOf(b));
        return left != null && left.equals(right);
    }

    /**
     * Compares definitions ignoring the declared name, which is what makes a rename detectable.
     * For RPCs only the owning service name is dropped — the method name stays part of the body, so
     * renaming a method reads as a removal plus an addition rather than as a service rename.
     */
    private static String stripName(String signature) {
        if (signature == null) {
            return null;
        }
        if (signature.startsWith("rpc ")) {
            int slash = signature.indexOf('/');
            return slash < 0 ? signature : signature.substring(slash);
        }
        int open = signature.indexOf('{');
        return open < 0 ? signature : signature.substring(open);
    }

    private static String describeOneof(String oneof) {
        return oneof == null ? "no oneof" : "oneof '" + oneof + "'";
    }

    private static String signatureOf(ContractExport export) {
        return comparisonSignature(export);
    }

    public static String comparisonSignature(ContractExport export) {
        if (export.metadata() == null) return null;
        Object signature = export.metadata().get("signature");
        if (signature == null) return null;
        if (export.type() == ContractModels.ContractType.MAVEN
                && export.metadata().get("javaApiComparison") instanceof Map<?, ?> javaDeclarations) {
            return String.valueOf(signature).lines()
                    .map(line -> javaDeclarations.containsKey(line) ? String.valueOf(javaDeclarations.get(line)) : line)
                    .sorted().collect(java.util.stream.Collectors.joining("\n"));
        }
        return String.valueOf(signature);
    }

    /** Identities of contracts with at least one breaking change, for impact routing. */
    public static List<ContractIdentity> breakingContracts(List<ContractChange> changes) {
        var seen = new LinkedHashMap<String, ContractIdentity>();
        changes.stream()
                .filter(ContractChange::isBreaking)
                .forEach(c -> seen.putIfAbsent(c.contractKey(), c.identity()));
        return List.copyOf(seen.values());
    }
}
