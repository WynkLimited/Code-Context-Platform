package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Deterministic fingerprint of a repository's contract surface.
 *
 * <p>This hash is the cache key for cross-repository analysis, so what it does and does not
 * include is a correctness property, not an optimisation:
 *
 * <ul>
 *   <li><b>Order-independent</b> — records are sorted before hashing, so reordering declarations
 *       in a {@code .proto} leaves it unchanged.</li>
 *   <li><b>Insensitive to irrelevant commits</b> — the commit SHA and generation timestamp are
 *       excluded, so a README-only commit in a consumer keeps its hash and the cached verdict
 *       stays valid instead of re-running analysis on every unrelated push.</li>
 *   <li><b>Sensitive to contract semantics</b> — every export and import contributes its canonical
 *       key and content hash.</li>
 *   <li><b>Sensitive to the toolchain that produced it</b> — schema, extractor, compatibility-rule
 *       and generator versions all contribute, so correcting a parser or a rule invalidates every
 *       index it produced rather than silently mixing two generations of output.</li>
 *   <li><b>Sensitive to source location</b> only through {@code sourceFile} on each record, which
 *       is deliberately <em>excluded</em>: moving an unchanged contract to a new path is not a
 *       contract change.</li>
 * </ul>
 */
public final class ContractIndexHasher {

    private static final String FIELD_SEPARATOR = "|";
    private static final String RECORD_SEPARATOR = ",";

    private ContractIndexHasher() {}

    /** Computes the hash for an index, ignoring any hash already set on it. */
    public static String hash(ContractIndex index) {
        return hash(
                index.owner(),
                index.repository(),
                index.exports(),
                index.imports(),
                index.indexSchemaVersion(),
                index.extractorVersions(),
                index.compatibilityRulesVersion(),
                index.generatorVersion());
    }

    public static String hash(
            String owner,
            String repository,
            List<ContractExport> exports,
            List<ContractImport> imports,
            String indexSchemaVersion,
            Map<String, String> extractorVersions,
            String compatibilityRulesVersion,
            String generatorVersion
    ) {
        String material = String.join(FIELD_SEPARATOR,
                "repo=" + lower(owner) + "/" + lower(repository),
                "schema=" + nullSafe(indexSchemaVersion),
                "extractors=" + joinSorted(extractorVersions.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue()).toList()),
                "rules=" + nullSafe(compatibilityRulesVersion),
                "generator=" + nullSafe(generatorVersion),
                "exports=" + joinSorted(exports.stream()
                        .map(e -> e.canonicalKey() + ":" + nullSafe(e.contentHash())).toList()),
                "imports=" + joinSorted(imports.stream()
                        .map(i -> i.canonicalKey() + ":" + nullSafe(i.dependencyVersion())).toList()));
        return sha256(material);
    }

    /**
     * Sorts and de-duplicates before joining. De-duplication matters for vendored copies: ten
     * byte-identical {@code Campaign.proto} files contribute one record, so adding an eleventh
     * identical copy is correctly not a contract change, while one copy diverging adds a new
     * {@code key:hash} pair and does move the hash.
     */
    private static String joinSorted(List<String> records) {
        return new TreeSet<>(records).stream().collect(Collectors.joining(RECORD_SEPARATOR));
    }

    static String sha256(String material) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of()
                    .formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
    }
}
