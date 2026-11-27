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

    public record Extraction(List<ContractExport> exports,List<ContractImport> imports) {
        public Extraction { exports=List.copyOf(exports);imports=List.copyOf(imports); }
    }
    public interface ExtractionCache {
        java.util.Optional<Extraction> find(ContractExtractor extractor);
        void save(ContractExtractor extractor,Extraction extraction);
    }
    /**
     * @param enabledExtractorIds extractor ids permitted by the product policy; {@code null} or
     *                            empty runs every registered extractor
     */
    public ContractIndex build(RepositoryContext repository, Set<String> enabledExtractorIds) {
        return build(repository,enabledExtractorIds,null);
    }
    public ContractIndex build(RepositoryContext repository, Set<String> enabledExtractorIds,ExtractionCache cache) {
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
            boolean reusable=cache!=null&&!extractor.incrementalInputSuffixes().isEmpty();
            var prior=reusable?cache.find(extractor):java.util.Optional.<Extraction>empty();
            Extraction extraction;
            try {
                extraction=prior.orElseGet(()->new Extraction(extractor.extractExports(repository),extractor.extractImports(repository)));
                List<ContractExport> extractedExports = extraction.exports();
                List<ContractImport> extractedImports = extraction.imports();
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
                continue;
            }
            // Cache/database failures are infrastructure failures, never partial extractor success.
            if(reusable&&prior.isEmpty())cache.save(extractor,extraction);
        }

        exports.sort(Comparator
                .comparing(ContractExport::canonicalKey)
                .thenComparing(e -> e.sourceFile() == null ? "" : e.sourceFile()));
        imports.sort(Comparator
                .comparing(ContractImport::canonicalKey)
                .thenComparing(i -> i.sourceFile() == null ? "" : i.sourceFile()));

        // Gradle dependency resolution is not implemented. Record an explicit coverage gap so
        // impact_analysis stays PARTIAL rather than silently treating Gradle repos as complete.
        if (hasGradleBuild(repository) && !hasMavenPom(repository)) {
            extractorVersions.putIfAbsent("gradle-dependencies", "unavailable:NOT_IMPLEMENTED");
            LOG.warnf("contract.index.gradle_unsupported repo=%s — dependency resolution coverage reduced",
                    repository.fullName());
        }

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

    private static boolean hasGradleBuild(RepositoryContext repository) {
        return java.nio.file.Files.isRegularFile(repository.root().resolve("build.gradle"))
                || java.nio.file.Files.isRegularFile(repository.root().resolve("build.gradle.kts"))
                || java.nio.file.Files.isRegularFile(repository.root().resolve("settings.gradle"))
                || java.nio.file.Files.isRegularFile(repository.root().resolve("settings.gradle.kts"));
    }

    private static boolean hasMavenPom(RepositoryContext repository) {
        return java.nio.file.Files.isRegularFile(repository.root().resolve("pom.xml"));
    }

    private static String shortSha(String sha) {
        return sha == null || sha.isBlank() ? "-" : sha.substring(0, Math.min(12, sha.length()));
    }

    private static String shortHash(String hash) {
        return hash == null ? "-" : hash.substring(0, Math.min(19, hash.length()));
    }
}
