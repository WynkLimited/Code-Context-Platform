package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.gnostic.GnosticModels.EvidenceType;
import com.wynk.codecontext.port.gnostic.GnosticModels.SourceRange;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Well-known {@link ContractModels.ContractExport}/{@link ContractModels.ContractImport} metadata keys. */
public final class ContractEntryMetadata {

    public static final String STABLE_SYMBOL_ID = "stableSymbolId";
    public static final String SOURCE_RANGE = "sourceRange";
    public static final String RESOLVED_SIGNATURE = "resolvedSignature";
    public static final String CONTRACT_FINGERPRINT = "contractFingerprint";
    public static final String EVIDENCE_TYPE = "evidenceType";
    public static final String GRAPH_NODE_ID = "graphNodeId";
    public static final String PARSER_VERSION = "parserVersion";
    public static final String EXPORT_ROLE = "exportRole";
    public static final String IMPORT_ROLE = "importRole";

    private ContractEntryMetadata() {}

    public static Map<String, Object> enrich(
            Map<String, Object> base,
            String stableSymbolId,
            SourceRange range,
            String resolvedSignature,
            String contractFingerprint,
            EvidenceType evidenceType,
            String graphNodeId,
            String parserVersion) {
        var out = new LinkedHashMap<String, Object>();
        if (base != null) {
            out.putAll(base);
        }
        putIfPresent(out, STABLE_SYMBOL_ID, stableSymbolId);
        if (range != null) {
            out.put(SOURCE_RANGE, Map.of("startLine", range.startLine(), "endLine", range.endLine()));
        }
        putIfPresent(out, RESOLVED_SIGNATURE, resolvedSignature);
        putIfPresent(out, CONTRACT_FINGERPRINT, contractFingerprint);
        if (evidenceType != null) {
            out.put(EVIDENCE_TYPE, evidenceType.name());
        }
        putIfPresent(out, GRAPH_NODE_ID, graphNodeId);
        putIfPresent(out, PARSER_VERSION, parserVersion);
        return Map.copyOf(out);
    }

    public static String stableSymbolId(Map<String, Object> metadata) {
        return string(metadata, STABLE_SYMBOL_ID);
    }

    public static String stringMetadata(Map<String, Object> metadata, String key) {
        return string(metadata, key);
    }

    public static EvidenceType evidenceType(Map<String, Object> metadata) {
        String raw = string(metadata, EVIDENCE_TYPE);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EvidenceType.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String stableSymbolKey(String file, String symbol, String commitSha) {
        String f = file == null ? "" : file.strip();
        String s = symbol == null ? "" : symbol.strip();
        String c = commitSha == null ? "" : commitSha.strip().toLowerCase(Locale.ROOT);
        return (f + "#" + s + "@" + c).toLowerCase(Locale.ROOT);
    }

    public static Map<String, Object> forRestEntry(
            Map<String, Object> base,
            String commitSha,
            String sourceFile,
            String stableSymbol,
            SourceRange range,
            String resolvedSignature,
            String contentFingerprint,
            boolean importRole,
            String parserVersion) {
        var enriched = enrich(
                base,
                stableSymbolKey(sourceFile, stableSymbol, commitSha),
                range,
                resolvedSignature,
                contentFingerprint,
                EvidenceType.STATIC_ANALYSIS,
                null,
                parserVersion);
        var out = new LinkedHashMap<>(enriched);
        out.put(importRole ? IMPORT_ROLE : EXPORT_ROLE, importRole ? "IMPORT" : "EXPORT");
        return Map.copyOf(out);
    }

    public static Map<String, Object> forMavenExport(
            Map<String, Object> base,
            String commitSha,
            String sourceFile,
            String coordinates,
            String resolvedSignature,
            String contentFingerprint,
            String parserVersion) {
        var enriched = enrich(
                base,
                stableSymbolKey(sourceFile, coordinates, commitSha),
                null,
                resolvedSignature,
                contentFingerprint,
                EvidenceType.BUILD_METADATA,
                null,
                parserVersion);
        var out = new LinkedHashMap<>(enriched);
        out.put(EXPORT_ROLE, "EXPORT");
        return Map.copyOf(out);
    }

    public static Map<String, Object> forMavenImport(
            Map<String, Object> base,
            String commitSha,
            String sourceFile,
            String coordinates,
            String dependencyVersion,
            String parserVersion) {
        var enriched = enrich(
                base,
                stableSymbolKey(sourceFile, coordinates + "@" + dependencyVersion, commitSha),
                null,
                coordinates,
                null,
                EvidenceType.BUILD_METADATA,
                null,
                parserVersion);
        var out = new LinkedHashMap<>(enriched);
        out.put(IMPORT_ROLE, "IMPORT");
        return Map.copyOf(out);
    }

    public static SourceRange lineRange(String content, int startIndex, int endIndex) {
        if (content == null || startIndex < 0) {
            return null;
        }
        int end = endIndex <= startIndex ? startIndex : Math.min(endIndex, content.length());
        return new SourceRange(lineOf(content, startIndex), lineOf(content, end));
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static void putIfPresent(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }

    private static String string(Map<String, Object> metadata, String key) {
        if (metadata == null || !metadata.containsKey(key)) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
