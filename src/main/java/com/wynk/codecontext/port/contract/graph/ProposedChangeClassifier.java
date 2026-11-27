package com.wynk.codecontext.port.contract.graph;

import java.util.Locale;
import java.util.Objects;

/**
 * Backward-compatible helpers for classifying proposed API/method changes supplied to
 * {@code impact_analysis}. Distinguishes binary-incompatible signature edits from
 * unknown behavioural changes without claiming definite runtime breakage.
 */
public final class ProposedChangeClassifier {
    public enum ChangeKind {
        METHOD_REMOVED,
        METHOD_RENAMED,
        SIGNATURE_CHANGED,
        RETURN_TYPE_CHANGED,
        VISIBILITY_REDUCED,
        BEHAVIOURAL_UNKNOWN,
        UNCHANGED
    }

    public record Classification(ChangeKind kind, String impactClass, String explanation) {}

    private ProposedChangeClassifier() {}

    public static Classification classify(String oldSignature, String newSignature) {
        String before = normalize(oldSignature);
        String after = normalize(newSignature);
        if (before.isEmpty() && after.isEmpty()) {
            return new Classification(ChangeKind.UNCHANGED, "NO_CHANGE", "No signatures were supplied.");
        }
        if (!before.isEmpty() && after.isEmpty()) {
            return new Classification(ChangeKind.METHOD_REMOVED, "POTENTIAL_BINARY_INCOMPATIBILITY",
                    "Existing signature removed; consumers resolving it may fail after upgrade.");
        }
        if (before.isEmpty()) {
            return new Classification(ChangeKind.UNCHANGED, "ADDITION_ONLY",
                    "Only a proposed signature was supplied; removal impact cannot be assessed.");
        }
        if (before.equals(after)) {
            return new Classification(ChangeKind.BEHAVIOURAL_UNKNOWN, "POTENTIAL_BEHAVIOURAL_IMPACT",
                    "Signatures match; behavioural changes are not proven by static analysis.");
        }
        String beforeName = methodName(before);
        String afterName = methodName(after);
        if (!Objects.equals(beforeName, afterName)) {
            return new Classification(ChangeKind.METHOD_RENAMED, "POTENTIAL_BINARY_INCOMPATIBILITY",
                    "Method name changed from " + beforeName + " to " + afterName + ".");
        }
        String beforeReturn = returnType(before);
        String afterReturn = returnType(after);
        if (!Objects.equals(beforeReturn, afterReturn) && parameterList(before).equals(parameterList(after))) {
            return new Classification(ChangeKind.RETURN_TYPE_CHANGED, "POTENTIAL_BINARY_INCOMPATIBILITY",
                    "Return type changed while parameters stayed the same.");
        }
        return new Classification(ChangeKind.SIGNATURE_CHANGED, "POTENTIAL_BINARY_INCOMPATIBILITY",
                "Method signature changed; treat as potential upgrade impact, not proven current breakage.");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String methodName(String signature) {
        int hash = signature.indexOf('#');
        int paren = signature.indexOf('(');
        if (hash >= 0 && paren > hash) return signature.substring(hash + 1, paren);
        if (paren > 0) return signature.substring(0, paren);
        return signature.toLowerCase(Locale.ROOT);
    }

    private static String parameterList(String signature) {
        int start = signature.indexOf('(');
        int end = signature.indexOf(')');
        if (start < 0 || end <= start) return "";
        return signature.substring(start, end + 1);
    }

    private static String returnType(String signature) {
        int end = signature.lastIndexOf(')');
        if (end < 0 || end + 1 >= signature.length()) return "";
        return signature.substring(end + 1).strip();
    }
}
