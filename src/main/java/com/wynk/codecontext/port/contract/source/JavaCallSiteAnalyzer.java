package com.wynk.codecontext.port.contract.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Deterministic, syntax-only method call evidence. A match is a candidate, never a breakage verdict. */
public final class JavaCallSiteAnalyzer {
    private JavaCallSiteAnalyzer() {}

    public static Result analyze(String path, String source, Set<String> candidateNames) {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null || source == null || !path.endsWith(".java")) return new Result(false, List.of());
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///Consumer.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        try {
            var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(input));
            var unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR)) return new Result(false, List.of());
            var positions = Trees.instance(task).getSourcePositions();
            var classes = new ArrayDeque<String>();
            var methods = new ArrayDeque<String>();
            var calls = new ArrayList<CallSite>();
            new TreePathScanner<Void, Void>() {
                @Override public Void visitClass(ClassTree node, Void unused) {
                    classes.addLast(node.getSimpleName().toString());
                    super.visitClass(node, unused);
                    classes.removeLast();
                    return null;
                }
                @Override public Void visitMethod(MethodTree node, Void unused) {
                    methods.addLast(node.getName().toString());
                    super.visitMethod(node, unused);
                    methods.removeLast();
                    return null;
                }
                @Override public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    String selector = node.getMethodSelect().toString();
                    String name = selector.substring(selector.lastIndexOf('.') + 1);
                    if (candidateNames.contains(name)) {
                        long start = positions.getStartPosition(unit, node), end = positions.getEndPosition(unit, node);
                        if (start >= 0 && end > start && end <= source.length()) {
                            calls.add(new CallSite(classes.isEmpty() ? null : String.join(".", classes),
                                    methods.isEmpty() ? null : methods.peekLast(), name,
                                    (int) unit.getLineMap().getLineNumber(start), (int) unit.getLineMap().getLineNumber(end - 1),
                                    source.substring((int) start, (int) end)));
                        }
                    }
                    return super.visitMethodInvocation(node, unused);
                }
            }.scan(unit, null);
            return new Result(true, List.copyOf(calls));
        } catch (Exception e) { return new Result(false, List.of()); }
    }

    public record CallSite(String className, String methodName, String referencedMethod,
                           int startLine, int endLine, String source) {}
    public record Result(boolean complete, List<CallSite> callSites) {}
}
