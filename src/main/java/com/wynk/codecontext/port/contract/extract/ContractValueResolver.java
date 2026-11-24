package com.wynk.codecontext.port.contract.extract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded literal/configuration resolution shared by contract extractors. */
final class ContractValueResolver {

    private static final Pattern CONSTANT = Pattern.compile(
            "\\b([A-Z][A-Z0-9_]*)\\s*=\\s*([^;\\n]+)");
    private static final Pattern PROPERTY = Pattern.compile(
            "(?m)^[ \\t]*([A-Za-z0-9_.-]+)[ \\t]*(?:=|:)[ \\t]*([^#\\s]*)");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^}:]+)(?::([^}]*))?}$");

    private ContractValueResolver() {}

    static Map<String, String> properties(List<ExtractorFileSupport.SourceFile> files) {
        var values = new LinkedHashMap<String, String>();
        for (var file : files) {
            String lower = file.path().toLowerCase(java.util.Locale.ROOT);
            if (!(lower.endsWith(".properties") || lower.endsWith(".yaml") || lower.endsWith(".yml"))) {
                continue;
            }
            addProperties(file.content(), values);
        }
        return Map.copyOf(values);
    }

    static Map<String, String> constants(String content) {
        var values = new LinkedHashMap<String, String>();
        Matcher matcher = CONSTANT.matcher(content);
        while (matcher.find()) {
            String value = matcher.group(2).strip();
            int quoteStart = value.indexOf('"');
            int quoteEnd = quoteStart < 0 ? -1 : value.indexOf('"', quoteStart + 1);
            if (quoteStart >= 0 && quoteEnd > quoteStart) {
                value = value.substring(quoteStart + 1, quoteEnd);
            }
            values.putIfAbsent(matcher.group(1), stripQuotes(value));
        }
        return Map.copyOf(values);
    }

    static String resolve(String expression, Map<String, String> constants, Map<String, String> properties) {
        if (expression == null) return null;
        String raw = expression.strip();
        boolean quotedLiteral = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"");
        String value = stripQuotes(raw);
        if (raw.contains("+")) {
            String composed = resolveConcatenation(raw, constants, properties);
            if (composed != null) {
                return composed;
            }
        }
        for (int i = 0; i < 2 && constants.containsKey(value); i++) value = constants.get(value);
        if (!quotedLiteral && !constants.containsKey(raw) && !value.startsWith("${")
                && !value.matches(".*[./:{}-].*")) return null;
        for (int i = 0; i < 4; i++) {
            Matcher placeholder = PLACEHOLDER.matcher(value);
            if (!placeholder.matches()) break;
            String resolved = properties.get(placeholder.group(1));
            value = resolved != null ? resolved : placeholder.group(2);
            if (value == null || value.isBlank()) return null;
        }
        if (value == null || value.isBlank() || value.contains("${")) {
            return null;
        }
        if (value.contains("+")) {
            return resolveConcatenation(value, constants, properties);
        }
        return value;
    }

    /**
     * Resolves simple {@code A + B} compositions of string literals and named constants only.
     * Does not evaluate arbitrary code.
     */
    static String resolveConcatenation(
            String expression,
            Map<String, String> constants,
            Map<String, String> properties) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        var parts = splitPlusOperands(expression);
        if (parts.size() < 2) {
            return null;
        }
        var resolved = new StringBuilder();
        for (String part : parts) {
            String token = part.strip();
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                resolved.append(stripQuotes(token));
                continue;
            }
            String value = constants.get(token);
            if (value == null) {
                value = resolve(token, constants, properties);
            }
            if (value == null || value.isBlank()) {
                return null;
            }
            resolved.append(value);
        }
        return resolved.isEmpty() ? null : resolved.toString();
    }

    private static java.util.List<String> splitPlusOperands(String expression) {
        var parts = new java.util.ArrayList<String>();
        var current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '"') {
                inString = !inString;
                current.append(c);
                continue;
            }
            if (!inString && c == '+') {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }

    private static String stripQuotes(String value) {
        if (value != null && value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void addProperties(String content, Map<String, String> values) {
        var yamlScopes = new java.util.ArrayDeque<YamlScope>();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.strip().startsWith("#")) continue;
            Matcher matcher = PROPERTY.matcher(line);
            if (!matcher.find()) continue;
            int indent = leadingSpaces(line);
            while (!yamlScopes.isEmpty() && yamlScopes.peek().indent() >= indent) yamlScopes.pop();
            String key = matcher.group(1);
            String value = stripQuotes(matcher.group(2).strip());
            String qualified = yamlScopes.isEmpty() ? key : yamlScopes.peek().key() + "." + key;
            if (value.isEmpty()) {
                yamlScopes.push(new YamlScope(indent, qualified));
            } else {
                values.putIfAbsent(qualified, value);
                values.putIfAbsent(key, value);
            }
        }
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(count))) count++;
        return count;
    }

    private record YamlScope(int indent, String key) {}
}
