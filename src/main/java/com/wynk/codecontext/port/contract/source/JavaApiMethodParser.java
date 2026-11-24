package com.wynk.codecontext.port.contract.source;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import javax.lang.model.element.Modifier;

/** Syntax-only extraction of public Java methods; no classpath or semantic claims are made. */
public final class JavaApiMethodParser {

    public record ApiMethod(String ownerFqn, String header, String name, boolean staticMethod,
            boolean varArgs, boolean implicitPublic, String comparisonHeader, String annotationSignature,
            int startOffset, int endOffset) {
        public ApiMethod(String ownerFqn, String header, String name, boolean staticMethod,
                boolean varArgs, boolean implicitPublic) {
            this(ownerFqn, header, name, staticMethod, varArgs, implicitPublic, header, "", -1, -1);
        }
        public ApiMethod(String ownerFqn, String header, String name, boolean staticMethod, boolean varArgs) {
            this(ownerFqn, header, name, staticMethod, varArgs, false);
        }
    }

    private JavaApiMethodParser() {}

    public static List<ApiMethod> parse(String sourceName, String source) {
        return extract(sourceName, source).methods();
    }

    public record Extraction(List<ApiMethod> methods, boolean complete) {
        public Extraction { methods = List.copyOf(methods); }
    }

    public static Extraction extract(String sourceName, String source) {
        if (source == null || source.isBlank()) {
            return new Extraction(List.of(), source != null);
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new Extraction(List.of(), false);
        }
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(uri(sourceName), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try {
            var task = (JavacTask) compiler.getTask(null, null, diagnostics,
                    List.of("-proc:none"), null, List.of(input));
            CompilationUnitTree unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR)) {
                return new Extraction(List.of(), false);
            }
            SourcePositions positions = Trees.instance(task).getSourcePositions();
            var methods = new ArrayList<ApiMethod>();
            String packageName = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            new TreePathScanner<Void, Void>() {
                private final Deque<String> owners = new ArrayDeque<>();
                private final Deque<Boolean> publicTypes = new ArrayDeque<>();
                private final Deque<Boolean> interfaces = new ArrayDeque<>();

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    owners.addLast(node.getSimpleName().toString());
                    boolean accessible = (node.getModifiers().getFlags().contains(Modifier.PUBLIC)
                            || Boolean.TRUE.equals(interfaces.peekLast())
                                && !node.getModifiers().getFlags().contains(Modifier.PRIVATE))
                            && (publicTypes.isEmpty() || publicTypes.peekLast());
                    publicTypes.addLast(accessible);
                    interfaces.addLast(node.getKind() == Tree.Kind.INTERFACE);
                    Void result = super.visitClass(node, unused);
                    interfaces.removeLast();
                    publicTypes.removeLast();
                    owners.removeLast();
                    return result;
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    Set<Modifier> flags = node.getModifiers().getFlags();
                    boolean implicitPublic = Boolean.TRUE.equals(interfaces.peekLast())
                            && !flags.contains(Modifier.PUBLIC) && !flags.contains(Modifier.PRIVATE)
                            && !flags.contains(Modifier.PROTECTED);
                    if (!owners.isEmpty()
                            && Boolean.TRUE.equals(publicTypes.peekLast())
                            && (flags.contains(Modifier.PUBLIC) || implicitPublic)) {
                        long start = positions.getStartPosition(unit, node);
                        long end = node.getBody() == null
                                ? positions.getEndPosition(unit, node)
                                : positions.getStartPosition(unit, node.getBody()) + 1;
                        if (start >= 0 && end > start && end <= source.length()) {
                            String owner = (packageName.isBlank() ? "" : packageName + ".")
                                    + String.join(".", owners);
                            String header = source.substring((int) start, (int) end)
                                    .replaceAll("\\s+", " ").strip();
                            if (implicitPublic) header = "public " + header;
                            String comparison = comparisonHeader(node, owners.peekLast(), implicitPublic);
                            methods.add(new ApiMethod(owner, header, node.getName().toString(),
                                    node.getModifiers().getFlags().contains(Modifier.STATIC),
                                    node.getParameters().stream().anyMatch(p -> p.toString().contains("...")), implicitPublic,
                                    comparison, annotationSignature(node), (int) start, (int) end));
                        }
                    }
                    return super.visitMethod(node, unused);
                }
            }.scan(unit, null);
            return new Extraction(methods, true);
        } catch (Exception ignored) {
            return new Extraction(List.of(), false);
        }
    }

    private static String comparisonHeader(MethodTree method, String ownerName, boolean implicitPublic) {
        var parts = new ArrayList<String>();
        if (implicitPublic) parts.add("public");
        String modifiers = String.join(" ", method.getModifiers().getFlags().stream().map(Object::toString).sorted().toList());
        if (!modifiers.isBlank()) parts.add(modifiers);
        if (!method.getTypeParameters().isEmpty()) parts.add("<" + String.join(", ",
                method.getTypeParameters().stream().map(Object::toString).toList()) + ">");
        if (method.getReturnType() != null) parts.add(method.getReturnType().toString());
        var parameters = new ArrayList<String>();
        for (int i = 0; i < method.getParameters().size(); i++) {
            var parameter = method.getParameters().get(i);
            String type = parameter.getType().toString();
            if (parameter.toString().endsWith("... " + parameter.getName()) && type.endsWith("[]")) {
                type = type.substring(0, type.length() - 2) + "...";
            }
            parameters.add(type + " p" + i);
        }
        String name = method.getReturnType() == null ? ownerName : method.getName().toString();
        parts.add(name + "(" + String.join(", ", parameters) + ")");
        if (!method.getThrows().isEmpty()) parts.add("throws " + String.join(", ", method.getThrows().stream().map(Object::toString).toList()));
        return String.join(" ", parts) + (method.getBody() == null ? ";" : " {");
    }

    private static String annotationSignature(MethodTree method) {
        var annotations = new ArrayList<String>();
        annotations.add(method.getModifiers().getAnnotations().toString());
        for (int i = 0; i < method.getParameters().size(); i++) {
            annotations.add(i + ":" + method.getParameters().get(i).getModifiers().getAnnotations());
        }
        return String.join("|", annotations);
    }

    private static URI uri(String name) {
        String safe = name == null || name.isBlank() ? "Source.java" : name.replace('\\', '/');
        int slash = safe.lastIndexOf('/');
        safe = slash < 0 ? safe : safe.substring(slash + 1);
        if (!safe.endsWith(".java")) {
            safe = "Source.java";
        }
        return URI.create("string:///" + safe);
    }
}
