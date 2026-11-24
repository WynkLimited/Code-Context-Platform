package com.wynk.codecontext.port.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The external contract surface of a repository: what it publishes and what it consumes.
 *
 * <p>This is deliberately <em>not</em> an AST. The AST answers "what is related inside this
 * repository"; the contract index answers "what crosses this repository's boundary". Keeping the
 * index small (kilobytes, not megabytes) is what makes cross-repository matching cheap enough to
 * run on every pull request.
 */
public final class ContractModels {

    /** Bumped whenever the serialized index shape changes; older indexes then read back as stale. */
    public static final String INDEX_SCHEMA_VERSION = "1.2.0";

    /** Bumped whenever index construction changes in a way that alters output for identical input. */
    public static final String GENERATOR_VERSION = "1.2.0";

    /**
     * Bumped whenever {@code ContractCompatibilityEngine} changes a verdict for unchanged input.
     * Participates in the index hash so a rules correction re-opens every cached analysis rather
     * than leaving stale BREAKING/COMPATIBLE conclusions in place.
     */
    public static final String COMPATIBILITY_RULES_VERSION = "1.4.1";

    private ContractModels() {}

    /**
     * Contract mechanisms by which repositories couple.
     *
     * <p>Each type must define identity, export extraction, import extraction, compatibility rules,
     * provenance and blocking eligibility before it ships. Phase A implements {@link #PROTO} and
     * {@link #GRPC} only.
     */
    public enum ContractType {
        PROTO,
        GRPC,
        MAVEN,
        KAFKA,
        REST,
        ENUM,
        CONFIG,
        CACHE,
        DATABASE,
        STRING_FORMAT;

        /** Lowercased id used in canonical keys and in {@code cross_repo.extractors} config. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Whether the version participates in contract <em>identity</em>.
         *
         * <p>False for every Phase A type, and that is load-bearing: a consumer pinned to
         * {@code campaign-grpc-common:2.3.0} must still match a producer publishing {@code 2.4.0},
         * otherwise no edge would ever form and version skew — the very thing we are looking for —
         * would become invisible. Version is carried alongside identity and fed to the
         * compatibility engine instead.
         */
        public boolean versionInIdentity() {
            return false;
        }

        public static Optional<ContractType> fromId(String id) {
            if (id == null || id.isBlank()) {
                return Optional.empty();
            }
            String needle = id.strip().toLowerCase(Locale.ROOT);
            for (ContractType type : values()) {
                if (type.id().equals(needle)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Deterministic identity of a single contract. {@code canonicalKey} is the join key used to
     * match an export in one repository against an import in another, so it must be derived the
     * same way on both sides — always through the {@code of*} factories, never hand-assembled.
     *
     * <pre>
     * proto:in.wynk.userGrowth.CampaignResponse
     * grpc:in.wynk.userGrowth.CampaignService/GetCampaign
     * maven:in.wynk:campaign-grpc-common
     * kafka:campaign-events
     * rest:GET:/campaign/{id}
     * </pre>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractIdentity(
            ContractType type,
            String namespace,
            String name,
            String operation,
            String version,
            String canonicalKey
    ) {

        public ContractIdentity {
            namespace = blankToNull(namespace);
            name = blankToNull(name);
            operation = blankToNull(operation);
            version = blankToNull(version);
            if (type == null) {
                throw new IllegalArgumentException("ContractIdentity requires a type");
            }
            if (canonicalKey == null || canonicalKey.isBlank()) {
                throw new IllegalArgumentException("ContractIdentity requires a canonicalKey");
            }
        }

        /** Fully qualified {@code namespace.name}, or just {@code name} when unqualified. */
        public String qualifiedName() {
            return namespace == null ? name : namespace + "." + name;
        }

        /** A protobuf message or enum: {@code proto:<package>.<Message>}. */
        public static ContractIdentity ofProto(String protoPackage, String messageName) {
            String qualified = qualify(protoPackage, messageName);
            return new ContractIdentity(ContractType.PROTO, blankToNull(protoPackage), messageName,
                    null, null, ContractType.PROTO.id() + ":" + qualified);
        }

        /** A gRPC method: {@code grpc:<package>.<Service>/<Method>}. */
        public static ContractIdentity ofGrpc(String protoPackage, String serviceName, String methodName) {
            String qualified = qualify(protoPackage, serviceName);
            return new ContractIdentity(ContractType.GRPC, blankToNull(protoPackage), serviceName,
                    methodName, null, ContractType.GRPC.id() + ":" + qualified + "/" + methodName);
        }

        /** A published Maven artifact: {@code maven:<groupId>:<artifactId>}, version excluded. */
        public static ContractIdentity ofMaven(String groupId, String artifactId, String version) {
            return new ContractIdentity(ContractType.MAVEN, groupId, artifactId, null,
                    blankToNull(version), ContractType.MAVEN.id() + ":" + groupId + ":" + artifactId);
        }

        /** A Kafka topic: {@code kafka:<topic>}. */
        public static ContractIdentity ofKafka(String topic) {
            return new ContractIdentity(ContractType.KAFKA, null, topic, null, null,
                    ContractType.KAFKA.id() + ":" + topic);
        }

        /** A REST endpoint: {@code rest:<METHOD>:<path>}. */
        public static ContractIdentity ofRest(String httpMethod, String path) {
            String method = httpMethod == null ? "" : httpMethod.strip().toUpperCase(Locale.ROOT);
            String normalizedPath = normalizePath(path);
            return new ContractIdentity(ContractType.REST, null, normalizedPath, method, null,
                    ContractType.REST.id() + ":" + method + ":" + normalizedPath);
        }

        /** A language enum shared across repository boundaries. */
        public static ContractIdentity ofEnum(String namespace, String enumName) {
            String qualified = qualify(namespace, enumName);
            return new ContractIdentity(ContractType.ENUM, blankToNull(namespace), enumName,
                    null, null, ContractType.ENUM.id() + ":" + qualified);
        }

        /** A configuration key or feature-flag name. */
        public static ContractIdentity ofConfig(String key) {
            return simpleIdentity(ContractType.CONFIG, key);
        }

        public static ContractIdentity ofFeatureFlag(String key) {
            return ofConfig(key);
        }

        /** A named framework cache or Redis key namespace. */
        public static ContractIdentity ofCache(String keySpace) {
            return simpleIdentity(ContractType.CACHE, keySpace);
        }

        public static ContractIdentity ofRedis(String keySpace) {
            return ofCache(keySpace);
        }

        /** A relational table or document collection. */
        public static ContractIdentity ofDatabase(String relation) {
            return simpleIdentity(ContractType.DATABASE, relation);
        }

        /** A URI, deeplink, identifier, or other explicitly declared string-format contract. */
        public static ContractIdentity ofStringFormat(String format) {
            return simpleIdentity(ContractType.STRING_FORMAT, format);
        }

        public static ContractIdentity ofUriFormat(String format) {
            return ofStringFormat(format);
        }

        public static ContractIdentity ofDeepLink(String format) {
            return ofStringFormat(format);
        }

        private static ContractIdentity simpleIdentity(ContractType type, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Contract identity requires a name");
            }
            String normalized = value.strip().toLowerCase(Locale.ROOT);
            return new ContractIdentity(type, null, normalized, null, null,
                    type.id() + ":" + normalized);
        }

        private static String qualify(String namespace, String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Contract identity requires a name");
            }
            return namespace == null || namespace.isBlank() ? name.strip() : namespace.strip() + "." + name.strip();
        }

        private static String normalizePath(String path) {
            if (path == null || path.isBlank()) {
                return "/";
            }
            String trimmed = path.strip();
            if (!trimmed.startsWith("/")) {
                trimmed = "/" + trimmed;
            }
            while (trimmed.length() > 1 && trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            return trimmed;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }

    /** A contract this repository publishes for others to depend on. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractExport(
            ContractIdentity contract,
            ContractType type,
            String sourceFile,
            String symbol,
            String contentHash,
            Map<String, Object> metadata
    ) {
        public ContractExport {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public String canonicalKey() {
            return contract.canonicalKey();
        }
    }

    /** A contract this repository depends on, published by someone else (or vendored locally). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractImport(
            ContractIdentity contract,
            ContractType type,
            String sourceFile,
            String symbol,
            String dependencyVersion,
            Map<String, Object> metadata
    ) {
        public ContractImport {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }

        public String canonicalKey() {
            return contract.canonicalKey();
        }
    }

    /**
     * A repository's contract surface at one commit, plus enough metadata to decide whether the
     * index is still usable (§9). An index whose extractor or schema version no longer matches the
     * running code is stale by definition and must not back a deterministic blocking decision.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractIndex(
            String owner,
            String repository,
            String commit,
            String branch,
            String indexSchemaVersion,
            Map<String, String> extractorVersions,
            String compatibilityRulesVersion,
            String generatorVersion,
            Instant generatedAt,
            String indexHash,
            List<ContractExport> exports,
            List<ContractImport> imports
    ) {
        public ContractIndex {
            extractorVersions = extractorVersions == null
                    ? Map.of()
                    : Map.copyOf(new LinkedHashMap<>(extractorVersions));
            exports = exports == null ? List.of() : List.copyOf(exports);
            imports = imports == null ? List.of() : List.copyOf(imports);
        }

        public String fullName() {
            return owner + "/" + repository;
        }

        /** Lowercased {@code owner/repo} identity, matching {@code ProductModels.RepoRef.key()}. */
        public String repoKey() {
            return fullName().toLowerCase(Locale.ROOT);
        }

        public ContractIndex withIndexHash(String hash) {
            return new ContractIndex(owner, repository, commit, branch, indexSchemaVersion,
                    extractorVersions, compatibilityRulesVersion, generatorVersion, generatedAt,
                    hash, exports, imports);
        }

        public List<ContractExport> exportsOf(ContractType type) {
            return exports.stream().filter(e -> e.type() == type).toList();
        }

        public List<ContractImport> importsOf(ContractType type) {
            return imports.stream().filter(i -> i.type() == type).toList();
        }

        public Optional<ContractExport> findExport(String canonicalKey) {
            return exports.stream().filter(e -> e.canonicalKey().equals(canonicalKey)).findFirst();
        }

        /** Every local definition of a contract; vendored proto copies are intentionally retained. */
        public List<ContractExport> findExports(String canonicalKey) {
            return exports.stream()
                    .filter(e -> e.canonicalKey().equals(canonicalKey))
                    .toList();
        }

        /** Every import matching {@code canonicalKey} — a contract may be consumed from many files. */
        public List<ContractImport> findImports(String canonicalKey) {
            return imports.stream().filter(i -> i.canonicalKey().equals(canonicalKey)).toList();
        }

        public boolean importsContract(String canonicalKey) {
            return imports.stream().anyMatch(i -> i.canonicalKey().equals(canonicalKey));
        }

        /** Distinct source files consuming {@code canonicalKey}, used to route file discovery. */
        public List<String> importingFiles(String canonicalKey) {
            return findImports(canonicalKey).stream()
                    .map(ContractImport::sourceFile)
                    .filter(f -> f != null && !f.isBlank())
                    .distinct()
                    .sorted()
                    .toList();
        }
    }

    /**
     * Usability of a stored index. Only {@link #CURRENT} may back a deterministic blocking
     * decision; every other state degrades to advisory (§14).
     */
    public enum IndexFreshness {
        /** Present, parseable, and generated by the running extractor/schema/rules versions. */
        CURRENT,
        /** Present but generated by different extractor/schema/compatibility-rule versions. */
        STALE,
        /** No index stored for this repository/commit. */
        MISSING,
        /** Present but unparseable, hash-inconsistent, or missing required metadata. */
        INVALID,
        /** A build is in progress for this repository/commit. */
        BUILDING,
        /** A build was attempted and failed. */
        FAILED;

        public boolean usableForDeterministicBlocking() {
            return this == CURRENT;
        }
    }
}
