package com.wynk.codecontext.port.model;

import com.wynk.codecontext.port.util.SeverityUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Product-level topology for cross-repository contract analysis.
 *
 * <p>The registry is a <em>safety and search boundary</em>, not a technical dependency graph:
 * it answers "which repositories are in scope for this product" and nothing more. Technical
 * dependencies are derived from per-repository contract indexes, never declared here.
 */
public final class ProductModels {

    private ProductModels() {}

    /** Canonical {@code owner/name} repository reference. Equality is case-insensitive. */
    public record RepoRef(String owner, String name) {

        public RepoRef {
            owner = owner == null ? "" : owner.strip();
            name = name == null ? "" : name.strip();
        }

        public String fullName() {
            return owner + "/" + name;
        }

        /** Lowercased identity used for map keys and deduplication. */
        public String key() {
            return fullName().toLowerCase(Locale.ROOT);
        }

        public boolean matches(String otherOwner, String otherName) {
            return key().equals(new RepoRef(otherOwner, otherName).key());
        }

        /**
         * Parses {@code "owner/name"} or a bare {@code "name"} (which adopts {@code defaultOwner}).
         *
         * @return empty when the value is blank or has more than one path separator
         */
        public static Optional<RepoRef> parse(String value, String defaultOwner) {
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            String[] parts = value.strip().split("/");
            if (parts.length == 2) {
                return parts[0].isBlank() || parts[1].isBlank()
                        ? Optional.empty()
                        : Optional.of(new RepoRef(parts[0], parts[1]));
            }
            if (parts.length == 1) {
                return defaultOwner == null || defaultOwner.isBlank()
                        ? Optional.empty()
                        : Optional.of(new RepoRef(defaultOwner, parts[0]));
            }
            return Optional.empty();
        }
    }

    /**
     * Deterministic-evidence class for a finding. Only the deterministic classes are ever
     * eligible for blocking; {@link #INFERRED} is LLM reasoning and stays advisory.
     */
    public enum FindingProvenance {
        CONTRACT_DRIFT,
        SIGNATURE_BREAK,
        INFERRED
    }

    /** Per-product cross-repository controls, including the kill switch. */
    public record CrossRepoPolicy(
            boolean enabled,
            boolean blocking,
            int maxImpactedRepos,
            String minSeverity,
            List<String> extractors,
            List<FindingProvenance> blockingProvenance,
            boolean coordinatedChangeDetection,
            boolean failClosed
    ) {

        /** Legacy compatibility field; zero means no repository-count cap. */
        public static final int DEFAULT_MAX_IMPACTED_REPOS = 0;
        public static final String DEFAULT_MIN_SEVERITY = "high";
        /** All extractors registered by the runtime; products may narrow this explicitly. */
        public static final List<String> DEFAULT_EXTRACTORS = List.of(
                "proto", "grpc", "maven", "kafka", "rest", "enum", "config", "cache",
                "database", "string_format");
        public static final List<FindingProvenance> DEFAULT_BLOCKING_PROVENANCE =
                List.of(FindingProvenance.CONTRACT_DRIFT, FindingProvenance.SIGNATURE_BREAK);

        public CrossRepoPolicy {
            extractors = normalizeLower(extractors, DEFAULT_EXTRACTORS);
            blockingProvenance = blockingProvenance == null || blockingProvenance.isEmpty()
                    ? DEFAULT_BLOCKING_PROVENANCE
                    : List.copyOf(new LinkedHashSet<>(blockingProvenance));
            minSeverity = minSeverity == null || minSeverity.isBlank()
                    ? DEFAULT_MIN_SEVERITY
                    : minSeverity.strip().toLowerCase(Locale.ROOT);
        }

        /** Stage 0 of the rollout: feature off unless a product opts in. */
        public static CrossRepoPolicy disabled() {
            return new CrossRepoPolicy(
                    false, false, DEFAULT_MAX_IMPACTED_REPOS, DEFAULT_MIN_SEVERITY,
                    DEFAULT_EXTRACTORS, DEFAULT_BLOCKING_PROVENANCE, true, false);
        }

        public boolean allowsExtractor(String extractorId) {
            return extractorId != null
                    && extractors.contains(extractorId.strip().toLowerCase(Locale.ROOT));
        }

        public boolean allowsBlockingFor(FindingProvenance provenance) {
            return blocking
                    && provenance != null
                    && provenance != FindingProvenance.INFERRED
                    && blockingProvenance.contains(provenance);
        }

        private static List<String> normalizeLower(List<String> values, List<String> fallback) {
            if (values == null || values.isEmpty()) {
                return fallback;
            }
            var out = new LinkedHashSet<String>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.strip().toLowerCase(Locale.ROOT));
                }
            }
            return out.isEmpty() ? fallback : List.copyOf(out);
        }
    }

    /**
     * One product: its member repositories, any explicitly configured dependencies that cross a
     * product boundary, and its cross-repository policy.
     */
    public record ProductDefinition(
            String id,
            String name,
            List<RepoRef> repos,
            List<RepoRef> externalDependencies,
            CrossRepoPolicy crossRepo,
            List<RepoRef> disabledRepos
    ) {

        public ProductDefinition(
                String id,
                String name,
                List<RepoRef> repos,
                List<RepoRef> externalDependencies,
                CrossRepoPolicy crossRepo) {
            this(id, name, repos, externalDependencies, crossRepo, List.of());
        }

        public ProductDefinition {
            repos = repos == null ? List.of() : List.copyOf(repos);
            externalDependencies = externalDependencies == null ? List.of() : List.copyOf(externalDependencies);
            crossRepo = crossRepo == null ? CrossRepoPolicy.disabled() : crossRepo;
            disabledRepos = disabledRepos == null ? List.of() : List.copyOf(disabledRepos);
        }

        public boolean contains(String owner, String repo) {
            return repos.stream().anyMatch(r -> r.matches(owner, repo));
        }

        public boolean isRepoDisabled(RepoRef repo) {
            if (repo == null || disabledRepos.isEmpty()) {
                return false;
            }
            return disabledRepos.stream().anyMatch(r -> r.key().equals(repo.key()));
        }

        /** Member repositories plus explicitly declared cross-product dependencies. */
        public List<RepoRef> allInScopeRepos() {
            var out = new LinkedHashMap<String, RepoRef>();
            repos.forEach(r -> out.putIfAbsent(r.key(), r));
            externalDependencies.forEach(r -> out.putIfAbsent(r.key(), r));
            return List.copyOf(out.values());
        }
    }

    /**
     * Lookup over all configured products.
     *
     * <p>A repository may belong to more than one product (§5). When it does, the candidate set is
     * the <em>union</em> of the owning products' in-scope repositories, while every safety control
     * takes its most conservative value across those products — see
     * {@link #effectivePolicy(String, String)}.
     */
    public static final class ProductRegistry {

        private final List<ProductDefinition> products;

        public ProductRegistry(List<ProductDefinition> products) {
            this.products = products == null ? List.of() : List.copyOf(products);
        }

        public static ProductRegistry empty() {
            return new ProductRegistry(List.of());
        }

        public List<ProductDefinition> products() {
            return products;
        }

        public Optional<ProductDefinition> byId(String id) {
            if (id == null) {
                return Optional.empty();
            }
            String needle = id.strip().toLowerCase(Locale.ROOT);
            return products.stream()
                    .filter(p -> p.id().toLowerCase(Locale.ROOT).equals(needle))
                    .findFirst();
        }

        /** Every product that lists {@code owner/repo} as a member, in configuration order. */
        public List<ProductDefinition> productsFor(String owner, String repo) {
            return products.stream().filter(p -> p.contains(owner, repo)).toList();
        }

        public List<ProductDefinition> productsFor(RepoRef repo) {
            return repo == null ? List.of() : productsFor(repo.owner(), repo.name());
        }

        /**
         * Repositories worth searching for impact from a change in {@code owner/repo}: the union of
         * in-scope repositories across every owning product, deduplicated, with the origin removed.
         * Products with cross-repo disabled contribute nothing.
         */
        public List<RepoRef> candidateRepos(String owner, String repo) {
            var origin = new RepoRef(owner, repo);
            var out = new LinkedHashMap<String, RepoRef>();
            for (ProductDefinition product : productsFor(owner, repo)) {
                if (!product.crossRepo().enabled()) {
                    continue;
                }
                for (RepoRef candidate : product.allInScopeRepos()) {
                    if (product.isRepoDisabled(candidate)) {
                        continue;
                    }
                    if (!candidate.key().equals(origin.key())) {
                        out.putIfAbsent(candidate.key(), candidate);
                    }
                }
            }
            return List.copyOf(out.values());
        }

        /**
         * Merges the policies of every product owning {@code owner/repo} into one deterministic
         * policy (§5). Search scope takes the permissive value so a known dependency is never
         * silently discarded; every safety control takes the conservative value:
         *
         * <ul>
         *   <li>{@code enabled} — OR (any owning product may open the search)</li>
         *   <li>{@code blocking} — AND (every owning product must permit blocking)</li>
         *   <li>{@code maxImpactedRepos} — MIN (tightest limit wins)</li>
         *   <li>{@code minSeverity} — MAX (strictest threshold wins)</li>
         *   <li>{@code extractors} / {@code blockingProvenance} — intersection (only what all trust)</li>
         *   <li>{@code coordinatedChangeDetection} / {@code failClosed} — OR (more checking wins)</li>
         * </ul>
         *
         * @return {@link CrossRepoPolicy#disabled()} when the repository belongs to no product
         */
        public CrossRepoPolicy effectivePolicy(String owner, String repo) {
            List<ProductDefinition> owning = productsFor(owner, repo);
            if (owning.isEmpty()) {
                return CrossRepoPolicy.disabled();
            }
            if (owning.size() == 1) {
                return owning.get(0).crossRepo();
            }

            boolean enabled = false;
            boolean blocking = true;
            boolean coordinated = false;
            boolean failClosed = false;
            int maxImpacted = Integer.MAX_VALUE;
            String minSeverity = null;
            Set<String> extractors = null;
            Set<FindingProvenance> provenance = null;

            for (ProductDefinition product : owning) {
                CrossRepoPolicy policy = product.crossRepo();
                enabled |= policy.enabled();
                blocking &= policy.blocking();
                coordinated |= policy.coordinatedChangeDetection();
                failClosed |= policy.failClosed();
                maxImpacted = Math.min(maxImpacted, policy.maxImpactedRepos());
                if (minSeverity == null
                        || SeverityUtils.rank(policy.minSeverity()) > SeverityUtils.rank(minSeverity)) {
                    minSeverity = policy.minSeverity();
                }
                extractors = intersect(extractors, policy.extractors());
                provenance = intersect(provenance, policy.blockingProvenance());
            }

            return new CrossRepoPolicy(
                    enabled,
                    blocking,
                    maxImpacted,
                    minSeverity,
                    extractors == null ? List.of() : List.copyOf(extractors),
                    provenance == null ? List.of() : List.copyOf(provenance),
                    coordinated,
                    failClosed);
        }

        public CrossRepoPolicy effectivePolicy(RepoRef repo) {
            return repo == null ? CrossRepoPolicy.disabled() : effectivePolicy(repo.owner(), repo.name());
        }

        /**
         * True when any owning product opens the cross-repo search. Callers still apply the
         * per-stage gates (changed exports, index freshness, limits) before doing real work.
         */
        public boolean crossRepoEnabledFor(String owner, String repo) {
            return effectivePolicy(owner, repo).enabled();
        }

        private static <T> Set<T> intersect(Set<T> accumulated, List<T> next) {
            if (accumulated == null) {
                return new LinkedHashSet<>(next);
            }
            var out = new LinkedHashSet<T>();
            for (T value : accumulated) {
                if (next.contains(value)) {
                    out.add(value);
                }
            }
            return out;
        }

        /** All distinct repositories mentioned by any product, for validation and diagnostics. */
        public List<RepoRef> allRepos() {
            var out = new LinkedHashMap<String, RepoRef>();
            for (ProductDefinition product : products) {
                for (RepoRef repo : product.allInScopeRepos()) {
                    out.putIfAbsent(repo.key(), repo);
                }
            }
            return List.copyOf(new ArrayList<>(out.values()));
        }

        public Map<String, List<String>> productIdsByRepo() {
            var out = new LinkedHashMap<String, List<String>>();
            for (ProductDefinition product : products) {
                for (RepoRef repo : product.repos()) {
                    out.computeIfAbsent(repo.key(), ignored -> new ArrayList<>()).add(product.id());
                }
            }
            return Map.copyOf(out);
        }
    }
}
