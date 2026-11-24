package com.wynk.codecontext.port.gnostic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChange;
import com.wynk.codecontext.port.model.ProductModels.RepoRef;

import java.util.List;

/** Technology-neutral Gnostic (Phase B) domain types. */
public final class GnosticModels {

    private GnosticModels() {}

    public static final String GNOSTIC_ANALYZER_VERSION = "1.0.0";

    public enum ChangeClassification {
        CONTRACT_CHANGE,
        IMPLEMENTATION_CHANGE,
        DEPENDENCY_CHANGE,
        CONFIGURATION_CHANGE,
        BUILD_CHANGE,
        GENERATED_ARTIFACT_CHANGE,
        EVENT_BEHAVIOR_CHANGE,
        DATABASE_SCHEMA_CHANGE,
        TEST_CHANGE,
        DOCUMENTATION_CHANGE,
        UNKNOWN
    }

    public enum EvidenceType {
        DETERMINISTIC,
        STATIC_ANALYSIS,
        AST_ANALYSIS,
        BUILD_METADATA,
        PACKAGE_METADATA,
        CONTRACT_ANALYSIS,
        RUNTIME_EVIDENCE,
        SEMANTIC_INFERENCE,
        LLM_INFERENCE,
        MANUAL
    }

    public enum UsageType {
        READ_FIELD,
        WRITE_FIELD,
        CALL_OPERATION,
        IMPLEMENT_OPERATION,
        SERIALIZE,
        DESERIALIZE,
        MAP,
        VALIDATE,
        PERSIST,
        FORWARD,
        TRANSFORM,
        LOG,
        METRIC,
        TEST,
        MOCK,
        UNKNOWN
    }

    public enum ImpactStatus {
        NOT_APPLICABLE,
        NO_IMPACT,
        AFFECTED,
        POTENTIALLY_AFFECTED,
        UNKNOWN
    }

    public enum CompatibilityStatus {
        COMPATIBLE,
        INCOMPATIBLE,
        CONDITIONALLY_COMPATIBLE,
        UNKNOWN
    }

    public enum ImpactMechanism {
        COMPILE_FAILURE,
        RUNTIME_FAILURE,
        DESERIALIZATION_FAILURE,
        SERIALIZATION_FAILURE,
        BEHAVIOR_CHANGE,
        DATA_LOSS,
        VALIDATION_FAILURE,
        TEST_FAILURE,
        NO_IMPACT,
        UNKNOWN
    }

    public enum Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    public enum GraphFreshness {
        FRESH,
        STALE,
        UNKNOWN
    }

    public enum AnalysisStatus {
        COMPLETED,
        PARTIAL,
        FAILED,
        NOT_RUN
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceRange(int startLine, int endLine) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChangedSymbol(
            String file,
            String symbol,
            String symbolType,
            String changeType,
            SourceRange sourceRange,
            String beforeSnippet,
            String afterSnippet,
            EvidenceType evidenceType,
            String declarationKey,
            Boolean evidenceComplete) {
        public ChangedSymbol(String file, String symbol, String symbolType, String changeType,
                SourceRange sourceRange, String beforeSnippet, String afterSnippet, EvidenceType evidenceType,
                String declarationKey) {
            this(file, symbol, symbolType, changeType, sourceRange, beforeSnippet, afterSnippet,
                    evidenceType, declarationKey, null);
        }
        public ChangedSymbol(String file, String symbol, String symbolType, String changeType,
                SourceRange sourceRange, String beforeSnippet, String afterSnippet, EvidenceType evidenceType) {
            this(file, symbol, symbolType, changeType, sourceRange, beforeSnippet, afterSnippet, evidenceType, null);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConsumerUsage(
            RepoRef repository,
            String commitSha,
            String file,
            String symbol,
            UsageType usageType,
            String changedElement,
            EvidenceType evidenceType) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImpactPath(List<String> repositories, List<String> symbols, String summary) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SemanticImpactResult(
            RepoRef consumerRepository,
            String contractKey,
            List<String> changedElements,
            List<ConsumerUsage> consumerUsages,
            ImpactStatus impactStatus,
            CompatibilityStatus compatibility,
            ImpactMechanism impactMechanism,
            String reasoning,
            Confidence confidence) {}

    /**
     * Evidence-backed cross-repository context for synthesis and publication (Phase B).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CrossRepoImpactContext(
            RepoRef sourceRepository,
            String sourceCommitSha,
            String baseCommitSha,
            List<ChangeClassification> classifications,
            List<ContractChange> contractChanges,
            List<ChangedSymbol> changedSymbols,
            List<ImpactPath> impactPaths,
            List<SemanticImpactResult> semanticResults,
            GraphFreshness graphFreshness,
            AnalysisStatus analysisStatus,
            List<String> warnings,
            ProducerImpactModels.Assessment producerImpact) {

        public CrossRepoImpactContext(RepoRef sourceRepository, String sourceCommitSha, String baseCommitSha,
                List<ChangeClassification> classifications, List<ContractChange> contractChanges,
                List<ChangedSymbol> changedSymbols, List<ImpactPath> impactPaths, List<SemanticImpactResult> semanticResults,
                GraphFreshness graphFreshness, AnalysisStatus analysisStatus, List<String> warnings) {
            this(sourceRepository, sourceCommitSha, baseCommitSha, classifications, contractChanges, changedSymbols,
                    impactPaths, semanticResults, graphFreshness, analysisStatus, warnings, ProducerImpactModels.Assessment.empty());
        }

        public CrossRepoImpactContext {
            classifications = classifications == null ? List.of() : List.copyOf(classifications);
            contractChanges = contractChanges == null ? List.of() : List.copyOf(contractChanges);
            changedSymbols = changedSymbols == null ? List.of() : List.copyOf(changedSymbols);
            impactPaths = impactPaths == null ? List.of() : List.copyOf(impactPaths);
            semanticResults = semanticResults == null ? List.of() : List.copyOf(semanticResults);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            producerImpact = producerImpact == null ? ProducerImpactModels.Assessment.empty() : producerImpact;
        }

        public static CrossRepoImpactContext empty(RepoRef source, String head, String base) {
            return new CrossRepoImpactContext(
                    source, head, base, List.of(), List.of(), List.of(), List.of(), List.of(),
                    GraphFreshness.UNKNOWN, AnalysisStatus.NOT_RUN, List.of());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ImplementationChangeResult(
            List<ChangedSymbol> symbols,
            List<ContractIdentity> stableContracts,
            List<ContractChange> syntheticChanges,
            String detail,
            ProducerImpactModels.Assessment producerImpact,
            List<ChangedSymbol> routingSymbols) {

        public ImplementationChangeResult(List<ChangedSymbol> symbols, List<ContractIdentity> stableContracts,
                List<ContractChange> syntheticChanges, String detail) {
            this(symbols, stableContracts, syntheticChanges, detail, ProducerImpactModels.Assessment.empty(), symbols);
        }

        public ImplementationChangeResult {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            stableContracts = stableContracts == null ? List.of() : List.copyOf(stableContracts);
            syntheticChanges = syntheticChanges == null ? List.of() : List.copyOf(syntheticChanges);
            producerImpact = producerImpact == null ? ProducerImpactModels.Assessment.empty() : producerImpact;
            routingSymbols = routingSymbols == null ? symbols : List.copyOf(routingSymbols);
        }

        public boolean hasChanges() {
            return !symbols.isEmpty() && !stableContracts.isEmpty();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GnosticChangeAnalysis(
            List<ChangeClassification> classifications,
            boolean contractTriggered,
            boolean implementationTriggered,
            ImplementationChangeResult implementation) {

        public GnosticChangeAnalysis {
            classifications = classifications == null ? List.of() : List.copyOf(classifications);
            implementation = implementation == null
                    ? new ImplementationChangeResult(List.of(), List.of(), List.of(), "")
                    : implementation;
        }

        public boolean anyTriggered() {
            return contractTriggered || implementationTriggered;
        }
    }
}
