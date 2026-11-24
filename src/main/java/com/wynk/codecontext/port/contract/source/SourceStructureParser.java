package com.wynk.codecontext.port.contract.source;

import com.wynk.codecontext.port.gnostic.GnosticModels.EvidenceType;
import com.wynk.codecontext.port.gnostic.GnosticModels.SourceRange;

import java.util.List;

/** Technology-agnostic structural source parse result for contract and change detection. */
public interface SourceStructureParser {

    String id();

    String version();

    boolean supportsPath(String relativePath);

    List<ParsedMethod> methods(String source, String relativePath);

    default Extraction extract(String source, String relativePath) {
        return new Extraction(methods(source, relativePath), true);
    }

    record Extraction(List<ParsedMethod> methods, boolean complete) {
        public Extraction { methods = List.copyOf(methods); }
    }

    record ParsedMethod(
            String name,
            String signatureKey,
            String rawBody,
            String normalizedBody,
            SourceRange range,
            EvidenceType evidenceType) {}
}
