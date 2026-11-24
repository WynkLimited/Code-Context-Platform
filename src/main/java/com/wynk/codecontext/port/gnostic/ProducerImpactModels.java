package com.wynk.codecontext.port.gnostic;

import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.gnostic.GnosticModels.ChangedSymbol;
import com.wynk.codecontext.port.model.ProductModels.RepoRef;
import java.util.List;

public final class ProducerImpactModels {
    private ProducerImpactModels() {}

    /** Source-declaration identity, not a compiler-resolved dispatch target. */
    public record Method(String module, String owner, String name, List<String> parameters,
            String returnType, String file, int startLine, int endLine, String header, String snippet,
            boolean exportedVisibility, String comparisonBody) {
        public Method(String module, String owner, String name, List<String> parameters,
                String returnType, String file, int startLine, int endLine, String header, String snippet,
                boolean exportedVisibility) {
            this(module, owner, name, parameters, returnType, file, startLine, endLine, header, snippet,
                    exportedVisibility, snippet);
        }
        public Method { parameters = List.copyOf(parameters); }
        public String descriptor() { return name + "(" + String.join(",", parameters) + ")"; }
        public String identity() { return module + "|" + owner + "#" + descriptor() + ":" + returnType; }
        public String declarationKey() { return module + "|" + owner + "#" + descriptor(); }
    }

    public enum Coverage {
        MATCH_FOUND, NO_EXPORTED_CALLER, DEPTH_LIMIT, UNRESOLVED_SYMBOL, AMBIGUOUS_SYMBOL,
        GRAPH_UNAVAILABLE, GRAPH_INCOMPLETE, UNSUPPORTED_SOURCE, BUDGET_EXCEEDED
    }

    public record Path(RepoRef producer, String commitSha, ChangedSymbol changedMethod,
            Method exportedMethod, ContractIdentity contract, List<Method> callPath, boolean complete) {
        public Path { callPath = List.copyOf(callPath); }
        public int depth() { return callPath.size() - 1; }
        public String summary() {
            return "reverse-callers @" + commitSha + ": " + String.join(" -> ",
                    callPath.stream().map(m -> m.owner() + "#" + m.descriptor()).toList());
        }
        public ChangedSymbol routingSymbol() {
            Method m = exportedMethod;
            return new ChangedSymbol(m.file(), m.owner() + "#" + m.descriptor(), "METHOD",
                    "TRANSITIVE_IMPLEMENTATION_CHANGE", new GnosticModels.SourceRange(m.startLine(), m.endLine()),
                    m.snippet(), m.snippet(), GnosticModels.EvidenceType.STATIC_ANALYSIS);
        }
    }

    public record Diagnostic(String file, String symbol, String commitSha, Coverage coverage,
            int nodesVisited, int edgesVisited, String detail) {}

    public record Assessment(List<Path> paths, List<Diagnostic> diagnostics) {
        public Assessment {
            paths = paths == null ? List.of() : List.copyOf(paths);
            diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
        }
        public static Assessment empty() { return new Assessment(List.of(), List.of()); }
        public boolean complete() {
            return diagnostics.stream().allMatch(d -> d.coverage() == Coverage.MATCH_FOUND
                    || d.coverage() == Coverage.NO_EXPORTED_CALLER);
        }
    }
}
