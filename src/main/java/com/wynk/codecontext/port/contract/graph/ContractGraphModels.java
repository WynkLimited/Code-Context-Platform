package com.wynk.codecontext.port.contract.graph;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.IndexFreshness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * The cross-repository contract graph, derived per query rather than materialised (§16, §17).
 *
 * <p>The graph is built from the changed contracts outwards — changed contract, then candidate
 * repositories from the product registry, then matching indexes — so its size is bounded by one
 * pull request's contract surface rather than by the organisation.
 *
 * <p>Contract identity is the only join key. Nothing here knows about Maven coordinates, service
 * discovery, or who owns what: those live in the index and the registry respectively.
 */
public final class ContractGraphModels {

    private ContractGraphModels() {}

    /** Which side of a contract a repository sits on. */
    public enum EdgeKind {
        EXPORTS,
        IMPORTS
    }

    /**
     * A repository participating in the graph.
     *
     * @param commit    the commit whose index produced this node
     * @param indexHash index hash at that commit, part of the analysis cache key (§31)
     * @param freshness usability of that index; anything but CURRENT bars deterministic blocking
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepositoryNode(
            String owner,
            String repository,
            String commit,
            String branch,
            String indexHash,
            IndexFreshness freshness) {

        public String fullName() {
            return owner + "/" + repository;
        }

        public String key() {
            return fullName().toLowerCase(Locale.ROOT);
        }
    }

    /** A contract as a graph vertex. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractNode(ContractIdentity identity) {

        public String key() {
            return identity.canonicalKey();
        }
    }

    /**
     * One repository's relationship to one contract.
     *
     * @param sourceFiles files in that repository that export or import the contract; this is what
     *                    consumer file discovery starts from
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractEdge(
            String repoKey,
            String contractKey,
            EdgeKind kind,
            List<String> sourceFiles) {

        public ContractEdge {
            sourceFiles = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
        }
    }

    /** The derived graph for one analysis. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContractGraph(
            List<RepositoryNode> repositories,
            List<ContractNode> contracts,
            List<ContractEdge> edges) {

        public ContractGraph {
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
            contracts = contracts == null ? List.of() : List.copyOf(contracts);
            edges = edges == null ? List.of() : List.copyOf(edges);
        }

        public static ContractGraph empty() {
            return new ContractGraph(List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return edges.isEmpty();
        }

        /** Repositories importing {@code contractKey}, in graph order. */
        public List<String> consumersOf(String contractKey) {
            return edges.stream()
                    .filter(e -> e.kind() == EdgeKind.IMPORTS && e.contractKey().equals(contractKey))
                    .map(ContractEdge::repoKey)
                    .distinct()
                    .toList();
        }

        /** Contracts {@code repoKey} imports, in graph order. */
        public List<String> importedContractsOf(String repoKey) {
            return edges.stream()
                    .filter(e -> e.kind() == EdgeKind.IMPORTS && e.repoKey().equals(repoKey))
                    .map(ContractEdge::contractKey)
                    .distinct()
                    .toList();
        }

        /** Files in {@code repoKey} that touch {@code contractKey}, deduplicated across edges. */
        public List<String> filesFor(String repoKey, String contractKey) {
            var files = new LinkedHashMap<String, String>();
            edges.stream()
                    .filter(e -> e.repoKey().equals(repoKey) && e.contractKey().equals(contractKey))
                    .flatMap(e -> e.sourceFiles().stream())
                    .forEach(f -> files.putIfAbsent(f, f));
            return List.copyOf(files.values());
        }

        public java.util.Optional<RepositoryNode> node(String repoKey) {
            return repositories.stream().filter(r -> r.key().equals(repoKey)).findFirst();
        }
    }
}
