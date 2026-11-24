package com.wynk.codecontext.port.util;

import java.util.Map;
import java.util.Set;

public final class SeverityUtils {

    private static final Set<String> CANONICAL = Set.of("blocker", "high", "medium", "low");

    /**
     * Maps common LLM severity synonyms to canonical labels. Crucially this both
     * <em>downgrades</em> noise (nit/info → low) and <em>upgrades</em> important labels
     * (critical/severe → blocker, major/error → high) so that a model emitting
     * "critical" is not silently demoted to "low" and filtered out.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            // → low
            Map.entry("minor", "low"),
            Map.entry("warning", "low"),
            Map.entry("warn", "low"),
            Map.entry("note", "low"),
            Map.entry("info", "low"),
            Map.entry("informational", "low"),
            Map.entry("nit", "low"),
            Map.entry("suggestion", "low"),
            Map.entry("trivial", "low"),
            Map.entry("cosmetic", "low"),
            Map.entry("style", "low"),
            // → medium
            Map.entry("moderate", "medium"),
            Map.entry("med", "medium"),
            Map.entry("normal", "medium"),
            // → high
            Map.entry("major", "high"),
            Map.entry("error", "high"),
            Map.entry("important", "high"),
            Map.entry("serious", "high"),
            // → blocker
            Map.entry("critical", "blocker"),
            Map.entry("crit", "blocker"),
            Map.entry("severe", "blocker"),
            Map.entry("fatal", "blocker"),
            Map.entry("must-fix", "blocker"),
            Map.entry("must_fix", "blocker")
    );

    private static final Map<String, Integer> ORDER = Map.of(
            "low", 0,
            "medium", 1,
            "high", 2,
            "blocker", 3
    );

    private SeverityUtils() {}

    /**
     * Maps LLM severity aliases and unknown values to canonical labels before filtering.
     * Known synonyms are upgraded/downgraded via {@link #ALIASES}; blank or unrecognized
     * severities fall back to {@code low}.
     */
    public static String normalizeSeverity(String severity) {
        String normalized = severity == null ? "" : severity.strip().toLowerCase();
        if (normalized.isEmpty()) {
            return "low";
        }
        if (CANONICAL.contains(normalized)) {
            return normalized;
        }
        String alias = ALIASES.get(normalized);
        if (alias != null) {
            return alias;
        }
        return "low";
    }

    /**
     * True when {@code severity} is a canonical label or a known alias.
     *
     * <p>{@link #normalizeSeverity} deliberately absorbs unknown values into {@code low} so a
     * creative LLM label never drops a finding. Configuration must not get that leniency — a
     * typo'd threshold would silently widen the gate — so config validation uses this instead.
     */
    public static boolean isRecognized(String severity) {
        if (severity == null || severity.isBlank()) {
            return false;
        }
        String normalized = severity.strip().toLowerCase();
        return CANONICAL.contains(normalized) || ALIASES.containsKey(normalized);
    }

    /**
     * Numeric rank of a severity (low=0 … blocker=3) after normalization.
     * Used to sort/prioritize findings so the most severe survive inline-comment caps.
     */
    public static int rank(String severity) {
        return ORDER.getOrDefault(normalizeSeverity(severity), 0);
    }

    public static boolean meetsMinimum(String severity, String minimum) {
        String normalized = normalizeSeverity(severity);
        int sev = ORDER.getOrDefault(normalized, -1);
        int min = ORDER.getOrDefault(minimum == null ? "" : minimum.toLowerCase(), 1);
        return sev >= min;
    }
}
