package com.wynk.codecontext.port.contract;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;

/** Delimiter-safe, deterministic finite scalar domain within persisted schema signatures. */
public record ScalarEnumDomain(String baseType, Set<String> values) {
    public ScalarEnumDomain { values = Set.copyOf(values); }
    public static String encode(String baseType, Collection<String> canonicalJsonValues) {
        return "enum64~" + encoded(baseType) + "~" + canonicalJsonValues.stream().map(ScalarEnumDomain::encoded)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)).stream().collect(java.util.stream.Collectors.joining("."));
    }
    public static ScalarEnumDomain parse(String token) {
        if (token == null || token.length() > 65536 || !token.startsWith("enum64~")) return null;
        try {
            String[] parts = token.split("~", -1);
            if (parts.length != 3 || parts[2].isEmpty()) return null;
            var values = new TreeSet<String>();
            for (String value : parts[2].split("\\.")) values.add(decoded(value));
            return new ScalarEnumDomain(decoded(parts[1]), Set.copyOf(values));
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static String encoded(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decoded(String value) { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
}
