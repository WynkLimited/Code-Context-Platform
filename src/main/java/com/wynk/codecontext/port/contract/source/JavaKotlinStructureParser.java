package com.wynk.codecontext.port.contract.source;

import com.wynk.codecontext.port.gnostic.GnosticModels.EvidenceType;
import com.wynk.codecontext.port.gnostic.GnosticModels.SourceRange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java uses the existing JDK declaration parser. Kotlin uses Tree-sitter when available,
 * with conservative source comparison and an explicitly incomplete scanner fallback.
 */

public class JavaKotlinStructureParser implements SourceStructureParser {

    public static final String VERSION = "1.1.0";

    private static final Pattern METHOD_HEAD = Pattern.compile(
            "(?m)^\\s*(?:public|protected)\\s+(?:static\\s+|final\\s+|synchronized\\s+)*"
                    + "(?:[\\w<>,\\[\\]?]+\\s+)+(\\w+)\\s*\\([^;]*\\)\\s*(?:throws[^{]+)?\\s*\\{");

    private final TreeSitterMethodParser treeSitter = TreeSitterMethodParser.tryCreate();

    @Override
    public String id() {
        return "java-kotlin-structure";
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public boolean supportsPath(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        String lower = relativePath.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt");
    }

    /** True when Tree-sitter native bindings parsed at least one method in this JVM. */
    public boolean treeSitterEnabled() {
        return treeSitter != null && treeSitter.available();
    }

    @Override
    public List<ParsedMethod> methods(String source, String relativePath) {
        return extract(source, relativePath).methods();
    }

    @Override
    public Extraction extract(String source, String relativePath) {
        if (source == null || source.isBlank() || !supportsPath(relativePath)) {
            return new Extraction(List.of(), source != null);
        }
        if (relativePath.toLowerCase(Locale.ROOT).endsWith(".java")) {
            var java = JavaMethodIdentityParser.extract(relativePath, source);
            if (java.complete()) {
                var methods = java.methods().stream().map(m -> new ParsedMethod(m.name(),
                        m.declarationKey(), m.snippet(), m.comparisonBody(),
                        new SourceRange(m.startLine(), m.endLine()), EvidenceType.AST_ANALYSIS)).toList();
                return new Extraction(methods, methods.stream().map(ParsedMethod::signatureKey).distinct().count() == methods.size());
            }
            // An unparsed source must never be compared using lossy whitespace normalization.
            return new Extraction(List.of(), false);
        }
        if (treeSitter != null) {
            List<ParsedMethod> parsed = treeSitter.parse(source, relativePath);
            if (!parsed.isEmpty()) {
                return new Extraction(parsed, true);
            }
        }
        return new Extraction(braceBalancedMethods(source), false);
    }

    private static List<ParsedMethod> braceBalancedMethods(String source) {
        var out = new ArrayList<ParsedMethod>();
        Map<String, ParsedMethod> dedupe = new LinkedHashMap<>();
        Matcher matcher = METHOD_HEAD.matcher(source);
        while (matcher.find()) {
            String name = matcher.group(1);
            int start = matcher.end() - 1;
            int end = matchingBraceEnd(source, start);
            if (end <= start) {
                continue;
            }
            String body = source.substring(start, end + 1);
            int startLine = lineOf(source, matcher.start());
            int endLine = lineOf(source, end);
            String key = name + "#" + startLine;
            dedupe.put(key, new ParsedMethod(
                    name,
                    key,
                    body,
                    normalize(body),
                    new SourceRange(startLine, endLine),
                    EvidenceType.STATIC_ANALYSIS));
        }
        out.addAll(dedupe.values());
        return List.copyOf(out);
    }

    private static int matchingBraceEnd(String source, int openBrace) {
        int depth = 0;
        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String normalize(String body) {
        return body;
    }
}
