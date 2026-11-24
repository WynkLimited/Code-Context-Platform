package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.source.JavaKotlinStructureParser;
import com.wynk.codecontext.port.gnostic.GnosticModels.EvidenceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** PostgreSQL-service adapter for the reference contract metadata enrichment semantics. */
public final class ContractIndexEnricher {
    public ContractIndex enrich(RepositoryContext repository, ContractIndex index) {
        if (index == null) return null;
        List<ContractExport> exports = new ArrayList<>(index.exports().size());
        for (ContractExport e : index.exports()) {
            exports.add(new ContractExport(e.contract(), e.type(), e.sourceFile(), e.symbol(), e.contentHash(),
                    enrichMetadata(repository, e.sourceFile(), e.symbol(), e.type(), e.metadata(), false)));
        }
        List<ContractImport> imports = new ArrayList<>(index.imports().size());
        for (ContractImport i : index.imports()) {
            imports.add(new ContractImport(i.contract(), i.type(), i.sourceFile(), i.symbol(), i.dependencyVersion(),
                    enrichMetadata(repository, i.sourceFile(), i.symbol(), i.type(), i.metadata(), true)));
        }
        return new ContractIndex(index.owner(), index.repository(), index.commit(), index.branch(),
                index.indexSchemaVersion(), index.extractorVersions(), index.compatibilityRulesVersion(),
                index.generatorVersion(), index.generatedAt(), index.indexHash(), exports, imports);
    }

    private static Map<String,Object> enrichMetadata(RepositoryContext repository, String file, String symbol,
                                                      ContractType type, Map<String,Object> base, boolean isImport) {
        String stable = ContractEntryMetadata.stableSymbolId(base);
        if (stable == null || stable.isBlank()) {
            String name = symbol == null || symbol.isBlank() ? (file == null ? "contract" : file) : symbol;
            stable = ContractEntryMetadata.stableSymbolKey(file, name, repository.commit());
        }
        EvidenceType evidence = ContractEntryMetadata.evidenceType(base);
        if (evidence == null) evidence = type == ContractType.MAVEN
                ? EvidenceType.BUILD_METADATA : EvidenceType.STATIC_ANALYSIS;
        String parserVersion = ContractEntryMetadata.stringMetadata(base, ContractEntryMetadata.PARSER_VERSION);
        if (parserVersion == null || parserVersion.isBlank())
            parserVersion = type == ContractType.REST ? JavaKotlinStructureParser.VERSION : "extractor";
        Map<String,Object> enriched = ContractEntryMetadata.enrich(base, stable, null, null, null,
                evidence, ContractEntryMetadata.stringMetadata(base, ContractEntryMetadata.GRAPH_NODE_ID), parserVersion);
        var result = new LinkedHashMap<>(enriched);
        result.put(isImport ? ContractEntryMetadata.IMPORT_ROLE : ContractEntryMetadata.EXPORT_ROLE,
                isImport ? "IMPORT" : "EXPORT");
        return Map.copyOf(result);
    }
}
