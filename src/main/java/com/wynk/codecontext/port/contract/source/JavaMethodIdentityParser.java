package com.wynk.codecontext.port.contract.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.wynk.codecontext.port.gnostic.ProducerImpactModels.Method;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** All Java method declarations, including private helpers. No type attribution or call inference. */
public final class JavaMethodIdentityParser {
    private JavaMethodIdentityParser() {}

    public static List<Method> parse(String path, String source) {
        return extract(path, source).methods();
    }

    public record Extraction(List<Method> methods, boolean complete) {
        public Extraction { methods = List.copyOf(methods); }
    }

    public static Extraction extract(String path, String source) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || source == null || !path.endsWith(".java")) return new Extraction(List.of(), false);
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///Source.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        try {
            var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(input));
            CompilationUnitTree unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return new Extraction(List.of(), false);
            var positions = Trees.instance(task).getSourcePositions();
            var result = new ArrayList<Method>();
            var names = new ArrayDeque<String>();
            var accessible = new ArrayDeque<Boolean>();
            var interfaces = new ArrayDeque<Boolean>();
            String pkg = unit.getPackageName() == null ? "" : unit.getPackageName() + ".";
            new TreePathScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree node, Void unused) {
                    names.addLast(node.getSimpleName().toString());
                    accessible.addLast((node.getModifiers().getFlags().contains(Modifier.PUBLIC)
                            || Boolean.TRUE.equals(interfaces.peekLast()) && !node.getModifiers().getFlags().contains(Modifier.PRIVATE))
                            && (accessible.isEmpty() || accessible.peekLast()));
                    interfaces.addLast(node.getKind() == Tree.Kind.INTERFACE);
                    super.visitClass(node, unused);
                    interfaces.removeLast(); accessible.removeLast(); names.removeLast();
                    return null;
                }
                @Override public Void visitMethod(MethodTree node, Void unused) {
                    if (names.isEmpty() || names.stream().anyMatch(String::isBlank)) return null;
                    long start = positions.getStartPosition(unit, node), end = positions.getEndPosition(unit, node);
                    long headerEnd = node.getBody() == null ? end : positions.getStartPosition(unit, node.getBody()) + 1;
                    if (start < 0 || end <= start || end > source.length() || headerEnd <= start) return null;
                    var flags = node.getModifiers().getFlags();
                    boolean implicit = Boolean.TRUE.equals(interfaces.peekLast()) && !flags.contains(Modifier.PRIVATE)
                            && !flags.contains(Modifier.PROTECTED) && !flags.contains(Modifier.PUBLIC);
                    String header = source.substring((int) start, (int) headerEnd).replaceAll("\\s+", " ").strip();
                    if (implicit) header = "public " + header;
                    int moduleEnd = path.indexOf("src/main/");
                    result.add(new Method(moduleEnd < 0 ? "!outside-main" : path.substring(0, moduleEnd),
                            pkg + String.join(".", names), node.getReturnType() == null ? names.peekLast() : node.getName().toString(),
                            node.getParameters().stream().map(p -> p.getType().toString().replaceAll("\\s+", "")).toList(),
                            node.getReturnType() == null ? "!constructor" : node.getReturnType().toString().replaceAll("\\s+", ""), path,
                            (int) unit.getLineMap().getLineNumber(start), (int) unit.getLineMap().getLineNumber(end - 1),
                            header, source.substring((int) start, (int) end),
                            Boolean.TRUE.equals(accessible.peekLast()) && (flags.contains(Modifier.PUBLIC) || implicit),
                            node.getBody() == null ? "" : node.getBody().toString()));
                    // Nested/local classes do not inherit the enclosing method's declaration identity.
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
            return new Extraction(result, true);
        } catch (Exception e) { return new Extraction(List.of(), false); }
    }
}
