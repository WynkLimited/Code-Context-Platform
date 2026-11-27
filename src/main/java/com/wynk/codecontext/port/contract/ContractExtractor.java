package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;

import java.util.List;

/**
 * Extracts one contract mechanism's surface from a repository checkout.
 *
 * <p>Extractors are independently versioned: {@link #version()} participates in the index hash, so
 * correcting an extractor invalidates every index it produced and forces a rebuild rather than
 * silently mixing outputs from two different parsers.
 *
 * <p>Implementations must be side-effect free with respect to the checkout, and must degrade to an
 * empty result rather than throwing when their toolchain is unavailable — a missing extractor
 * reduces coverage, it must never fail the enclosing review.
 */
public interface ContractExtractor {

    /** Stable id, matching {@code ContractType.id()} and the {@code cross_repo.extractors} config. */
    String id();

    /** Semantic version of this extractor's output. Participates in the index hash. */
    String version();

    /** The contract type this extractor produces. */
    ContractType type();

    /** True when the toolchain this extractor needs is present on this machine. */
    boolean available();

    /**
     * True when a changed file at this repository-relative path could define or remove a contract
     * of this type. Used as a cost gate so that implementation-only pull requests never pay for an
     * index build.
     *
     * <p>Defaults to true: an extractor that cannot cheaply decide must say so by over-triggering,
     * because the failure mode of under-triggering is a missed breaking change.
     */
    default boolean mayDefineContract(String repoRelativePath) {
        return true;
    }

    /** Complete raw-extraction input suffixes. Empty disables reuse. Opt in only when
     * outputs are commit-independent and no other files/toolchain inputs are consulted. */
    default java.util.Set<String> incrementalInputSuffixes() { return java.util.Set.of(); }

    List<ContractExport> extractExports(RepositoryContext repository);

    List<ContractImport> extractImports(RepositoryContext repository);
}
