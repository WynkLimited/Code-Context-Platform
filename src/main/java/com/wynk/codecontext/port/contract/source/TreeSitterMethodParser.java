package com.wynk.codecontext.port.contract.source;

import ch.usi.si.seart.treesitter.Language;
import ch.usi.si.seart.treesitter.Node;
import ch.usi.si.seart.treesitter.Parser;
import ch.usi.si.seart.treesitter.Tree;
import ch.usi.si.seart.treesitter.exception.parser.ParsingException;
import com.wynk.codecontext.port.gnostic.GnosticModels.EvidenceType;
import com.wynk.codecontext.port.gnostic.GnosticModels.SourceRange;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Tree-sitter method extraction using {@code java-tree-sitter} (native library bundled in the JAR). */
final class TreeSitterMethodParser {

    private static final Logger LOG = Logger.getLogger(TreeSitterMethodParser.class);

    private final Parser javaParser;
    private final Parser kotlinParser;

    private TreeSitterMethodParser(Parser javaParser, Parser kotlinParser) {
        this.javaParser = javaParser;
        this.kotlinParser = kotlinParser;
    }

    static TreeSitterMethodParser tryCreate() {
        try {
            Parser java = Parser.builder().language(Language.JAVA).build();
            Parser kotlin = Parser.builder().language(Language.KOTLIN).build();
            return new TreeSitterMethodParser(java, kotlin);
        } catch (LinkageError | Exception e) {
            LOG.debugf("contract.source.treesitter.unavailable reason=%s", e.toString());
            return null;
        }
    }

    boolean available() {
        return javaParser != null && kotlinParser != null;
    }

    List<SourceStructureParser.ParsedMethod> parse(String source, String relativePath) {
        Parser parser = parserFor(relativePath);
        if (parser == null || source == null || source.isBlank()) {
            return List.of();
        }
        try (Tree tree = parser.parse(source)) {
            return walkMethods(tree.getRootNode(), source);
        } catch (ParsingException e) {
            LOG.debugf("contract.source.treesitter.parse_failed file=%s reason=%s",
                    relativePath, e.getMessage());
            return List.of();
        }
    }

    private Parser parserFor(String relativePath) {
        if (relativePath == null) {
            return javaParser;
        }
        return relativePath.toLowerCase(Locale.ROOT).endsWith(".kt") ? kotlinParser : javaParser;
    }

    private static List<SourceStructureParser.ParsedMethod> walkMethods(Node node, String source) {
        var out = new ArrayList<SourceStructureParser.ParsedMethod>();
        if (node == null) {
            return out;
        }
        String type = node.getType();
        if ("method_declaration".equals(type) || "function_declaration".equals(type)) {
            int startByte = node.getStartByte();
            int endByte = node.getEndByte();
            if (startByte >= 0 && endByte > startByte && endByte <= source.length()) {
                String snippet = source.substring(startByte, endByte);
                String name = extractName(snippet);
                int startLine = lineOf(source, startByte);
                int endLine = lineOf(source, endByte);
                out.add(new SourceStructureParser.ParsedMethod(
                        name,
                        name + "#" + startLine,
                        snippet,
                        snippet,
                        new SourceRange(startLine, endLine),
                        EvidenceType.AST_ANALYSIS));
            }
        }
        for (Node child : node.getChildren()) {
            out.addAll(walkMethods(child, source));
        }
        return out;
    }

    private static String extractName(String snippet) {
        var matcher = java.util.regex.Pattern.compile("\\b(\\w+)\\s*\\(").matcher(snippet);
        return matcher.find() ? matcher.group(1) : "unknown";
    }

    private static int lineOf(String text, int byteIndex) {
        int line = 1;
        for (int i = 0; i < byteIndex && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
