package com.wynk.codecontext.port.contract.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProposedChangeClassifierTest {
    @Test void classifiesRemovalRenameReturnAndSignatureChanges() {
        var removed = ProposedChangeClassifier.classify("org.acme.Money#convert(java.lang.String)java.lang.String", "");
        assertEquals(ProposedChangeClassifier.ChangeKind.METHOD_REMOVED, removed.kind());
        assertEquals("POTENTIAL_BINARY_INCOMPATIBILITY", removed.impactClass());

        var renamed = ProposedChangeClassifier.classify(
                "org.acme.Money#convert(java.lang.String)java.lang.String",
                "org.acme.Money#transform(java.lang.String)java.lang.String");
        assertEquals(ProposedChangeClassifier.ChangeKind.METHOD_RENAMED, renamed.kind());

        var returnType = ProposedChangeClassifier.classify(
                "org.acme.Money#convert(java.lang.String)java.lang.String",
                "org.acme.Money#convert(java.lang.String)java.math.BigDecimal");
        assertEquals(ProposedChangeClassifier.ChangeKind.RETURN_TYPE_CHANGED, returnType.kind());

        var signature = ProposedChangeClassifier.classify(
                "org.acme.Money#convert(java.lang.String)java.lang.String",
                "org.acme.Money#convert(int)java.lang.String");
        assertEquals(ProposedChangeClassifier.ChangeKind.SIGNATURE_CHANGED, signature.kind());

        var same = ProposedChangeClassifier.classify(
                "org.acme.Money#convert(java.lang.String)java.lang.String",
                "org.acme.Money#convert(java.lang.String)java.lang.String");
        assertEquals(ProposedChangeClassifier.ChangeKind.BEHAVIOURAL_UNKNOWN, same.kind());
    }
}
