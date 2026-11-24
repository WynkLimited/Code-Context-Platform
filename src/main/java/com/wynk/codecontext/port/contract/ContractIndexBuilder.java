package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds a {@link ContractIndex} for one repository at one commit by running the enabled
 * extractors over a checkout.
 *
 * <p>An extractor that is unavailable or throws contributes nothing and is recorded, rather than
 * failing the build: partial coverage degrades a cross-repo finding to advisory, whereas a thrown
 * exception here would surface as a failed pull-request review.
 */

public class ContractIndexBuilder {

    private static final Logger LOG = Logger.getLogger(ContractIndexBuilder.class);

    private final List<ContractExtractor> extractors;
    private final ContractIndexEnricher enricher;

    

    /** Test constructor. */
    public ContractIndexBuilder(List<ContractExtractor> extractors) {
        this(extractors, new ContractIndexEnricher());
    }

    /** Test constructor with enricher. */
    public ContractIndexBuilder(List<ContractExtractor> extractors, ContractIndexEnricher enricher) {
        this.extractors = extractors.stream()
                .sorted(Comparator.comparing(ContractExtractor::id))
                .toList();
        this.enricher = enricher;
    }

    public List<ContractExtractor> extractors() {
        return extractors;
    }

    /**
     * @param enabledExtractorIds extractor ids permitted by the product policy; {@code null} or
     *                            empty runs every registered extractor
     */
    public ContractIndex build(RepositoryContext repository, Set<String> enabledExtractorIds) {
        var exports = new ArrayList<ContractExport>();
        var imports = new ArrayList<ContractImport>();
        var extractorVersions = new TreeMap<String, String>();

        for (ContractExtractor extractor : extractors) {
            if (!isEnabled(extractor, enabledExtractorIds)) {
                continue;
            }
            if (!extractor.available()) {
                extractorVersions.put(extractor.id(), "unavailable:" + extractor.version());
                LOG.warnf("contract.index.extractor_unavailable repo=%s extractor=%s — coverage reduced",
                        repository.fullName(), extractor.id());
                continue;
            }
            try {
                List<ContractExport> extractedExports = extractor.extractExports(repository);
                List<ContractImport> extractedImports = extractor.extractImports(repository);
                exports.addAll(extractedExports);
                imports.addAll(extractedImports);
                extractorVersions.put(extractor.id(), extractor.version());
                LOG.debugf("contract.index.extracted repo=%s extractor=%s exports=%d imports=%d",
                        repository.fullName(), extractor.id(),
                        extractedExports.size(), extractedImports.size());
            } catch (RuntimeException e) {
                extractorVersions.put(extractor.id(), "failed:" + extractor.version());
                LOG.warnf(e, "contract.index.extractor_failed repo=%s extractor=%s — skipping",
                        repository.fullName(), extractor.id());
            }
        }

        exports.sort(Comparator
                .comparing(ContractExport::canonicalKey)
                .thenComparing(e -> e.sourceFile() == null ? "" : e.sourceFile()));
        imports.sort(Comparator
                .comparing(ContractImport::canonicalKey)
                .thenComparing(i -> i.sourceFile() == null ? "" : i.sourceFile()));

        var index = new ContractIndex(
                repository.owner(),
                repository.repository(),
                repository.commit(),
                repository.branch(),
                ContractModels.INDEX_SCHEMA_VERSION,
                extractorVersions,
                ContractModels.COMPATIBILITY_RULES_VERSION,
                ContractModels.GENERATOR_VERSION,
                Instant.now(),
                null,
                List.copyOf(exports),
                List.copyOf(imports));

        ContractIndex enriched = enricher.enrich(repository, index);
        ContractIndex hashed = enriched.withIndexHash(ContractIndexHasher.hash(enriched));
        LOG.infof("contract.index.built repo=%s commit=%s exports=%d imports=%d extractors=%s hash=%s",
                repository.fullName(), shortSha(repository.commit()),
                hashed.exports().size(), hashed.imports().size(),
                extractorVersions.keySet(), shortHash(hashed.indexHash()));
        return hashed;
    }

    public ContractIndex build(RepositoryContext repository) {
        return build(repository, Set.of());
    }

    private static boolean isEnabled(ContractExtractor extractor, Set<String> enabledExtractorIds) {
        if (enabledExtractorIds == null || enabledExtractorIds.isEmpty()) {
            return true;
        }
        return enabledExtractorIds.stream()
                .anyMatch(id -> id != null && id.strip().toLowerCase(Locale.ROOT).equals(extractor.id()));
    }

    private static String shortSha(String sha) {
        return sha == null || sha.isBlank() ? "-" : sha.substring(0, Math.min(12, sha.length()));
    }

    private static String shortHash(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(19, hash.length()));
    }
}
