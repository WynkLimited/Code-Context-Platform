package com.wynk.codecontext.port.contract.source;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.MethodAmbiguityException;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** JavaParser Symbol Solver adapter. Failed bindings stay unresolved and are never guessed by name. */
public final class JavaSemanticCallSiteAnalyzer {
    private JavaSemanticCallSiteAnalyzer() {}

    public static List<MethodSymbol> declaredMethods(Path sourceFile, List<Path> sourceRoots, List<Path> classpathJars) {
        var types = typeSolver(sourceRoots, classpathJars);
        if (types == null) return List.of();
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(types));
        try {
            var parsed = new JavaParser(config).parse(sourceFile);
            if (parsed.getResult().isEmpty()) return List.of();
            var symbols = new ArrayList<MethodSymbol>();
            var unit = parsed.getResult().get();
            for (MethodDeclaration method : unit.findAll(MethodDeclaration.class)) try {
                symbols.add(new MethodSymbol(method.resolve().declaringType().getQualifiedName(), method.resolve().getSignature(), "METHOD"));
            } catch (RuntimeException ignored) { }
            for (var constructor : unit.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class)) try {
                var resolved = constructor.resolve();
                symbols.add(new MethodSymbol(resolved.declaringType().getQualifiedName(), resolved.getSignature(), "CONSTRUCTOR"));
            } catch (RuntimeException ignored) { }
            return List.copyOf(symbols);
        } catch (IOException | RuntimeException e) { return List.of(); }
    }

    public static Result analyze(Path sourceFile, List<Path> sourceRoots, List<Path> classpathJars) {
        var types = typeSolver(sourceRoots, classpathJars);
        if (types == null) return new Result(false, List.of(), "TYPE_SOLVER_CONFIGURATION_FAILED");
        var config = new ParserConfiguration().setSymbolResolver(new JavaSymbolSolver(types));
        try {
            var parse = new JavaParser(config).parse(sourceFile);
            if (parse.getResult().isEmpty()) return new Result(false, List.of(), "SOURCE_PARSE_FAILED");
            CompilationUnit unit = parse.getResult().get();
            var calls = new ArrayList<CallSite>();
            var methods = new ArrayDeque<String>();
            unit.accept(new VoidVisitorAdapter<Void>() {
                @Override public void visit(MethodDeclaration node, Void arg) {
                    String signature;
                    try { signature = node.resolve().getSignature(); }
                    catch (RuntimeException ignored) { signature = node.getSignature().asString(); }
                    methods.addLast(signature);
                    super.visit(node, arg);
                    methods.removeLast();
                }
                @Override public void visit(MethodCallExpr node, Void arg) {
                    try {
                        ResolvedMethodDeclaration resolved = node.resolve();
                        calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1),
                                enclosingClass(node), methods.peekLast(), "RESOLVED", resolved.declaringType().getQualifiedName(), resolved.getSignature(), params(resolved), resolved.getReturnType().describe()).withDeclaration(resolved));
                    } catch (MethodAmbiguityException ex) { calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1), enclosingClass(node), methods.peekLast(), "AMBIGUOUS", null, null, List.of(), null)); }
                    catch (RuntimeException ex) { calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1), enclosingClass(node), methods.peekLast(), "UNRESOLVED", null, null, List.of(), null)); }
                    super.visit(node, arg);
                }
                @Override public void visit(ObjectCreationExpr node, Void arg) {
                    try {
                        var resolved = node.resolve();
                        calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1),
                                enclosingClass(node), methods.peekLast(), "RESOLVED", resolved.declaringType().getQualifiedName(), resolved.getSignature(), params(resolved), "<constructor>").withDeclaration(resolved));
                    } catch (MethodAmbiguityException ex) { calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1), enclosingClass(node), methods.peekLast(), "AMBIGUOUS", null, null, List.of(), null)); }
                    catch (RuntimeException ex) { calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1), enclosingClass(node), methods.peekLast(), "UNRESOLVED", null, null, List.of(), null)); }
                    super.visit(node, arg);
                }
                @Override public void visit(MethodReferenceExpr node, Void arg) {
                    try {
                        ResolvedMethodDeclaration resolved = node.resolve();
                        calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1),
                                enclosingClass(node), methods.peekLast(), "RESOLVED", resolved.declaringType().getQualifiedName(), resolved.getSignature(), params(resolved), resolved.getReturnType().describe()).withDeclaration(resolved));
                    } catch (MethodAmbiguityException ex) { calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1), enclosingClass(node), methods.peekLast(), "AMBIGUOUS", null, null, List.of(), null)); }
                    catch (RuntimeException ex) { calls.add(site(node.toString(), node.getBegin().map(p -> p.line).orElse(-1), node.getEnd().map(p -> p.line).orElse(-1), enclosingClass(node), methods.peekLast(), "UNRESOLVED", null, null, List.of(), null)); }
                    super.visit(node, arg);
                }
            }, null);
            return new Result(true, List.copyOf(calls), null);
        } catch (IOException | RuntimeException e) { return new Result(false, List.of(), "SOURCE_READ_OR_ANALYSIS_FAILED"); }
    }

    private static CombinedTypeSolver typeSolver(List<Path> sourceRoots, List<Path> classpathJars) {
        var types = new CombinedTypeSolver();
        types.add(new ReflectionTypeSolver());
        try {
            for (Path root : sourceRoots) if (root != null && root.toFile().isDirectory()) types.add(new JavaParserTypeSolver(root));
            for (Path jar : classpathJars) if (jar != null && jar.toFile().isFile() && jar.toString().endsWith(".jar")) types.add(new JarTypeSolver(jar));
            return types;
        } catch (IOException e) { return null; }
    }

    private static String enclosingClass(com.github.javaparser.ast.Node node) {
        var names = new ArrayDeque<String>();
        com.github.javaparser.ast.Node current=node;
        while(current!=null){if(current instanceof com.github.javaparser.ast.body.TypeDeclaration<?> type)names.addFirst(type.getNameAsString());current=current.getParentNode().orElse(null);}
        if(names.isEmpty())return null;
        String pkg=node.findCompilationUnit().flatMap(com.github.javaparser.ast.CompilationUnit::getPackageDeclaration).map(p->p.getNameAsString()).orElse("");
        return (pkg.isBlank()?"":pkg+".")+String.join(".",names);
    }
    private static List<String> params(ResolvedMethodLikeDeclaration m) {
        var out = new ArrayList<String>();
        for (int i = 0; i < m.getNumberOfParams(); i++) out.add(m.getParam(i).describeType());
        return List.copyOf(out);
    }
    private static CallSite site(String expression, int start, int end, String callerClass, String caller, String status,
                                 String owner, String signature, List<String> params, String returns) {
        return new CallSite(expression, start, end, callerClass, caller, status, owner, signature, params, returns,
                status.equals("RESOLVED") ? "JAVAPARSER_SYMBOL_SOLVER" : status.equals("AMBIGUOUS") ? "MULTIPLE_APPLICABLE_SYMBOLS" : "NO_TYPE_RESOLUTION");
    }
    public record CallSite(String expression, int startLine, int endLine, String callerClass, String callerMethod, String resolutionStatus,
                           String declaringType, String signature, List<String> parameterTypes, String returnType, String evidence,
                           String declarationOrigin,String binaryClassName) {
        public CallSite(String expression,int startLine,int endLine,String callerClass,String callerMethod,String resolutionStatus,
                        String declaringType,String signature,List<String> parameterTypes,String returnType,String evidence) {
            this(expression,startLine,endLine,callerClass,callerMethod,resolutionStatus,declaringType,signature,parameterTypes,returnType,evidence,"UNKNOWN",null);
        }
        private CallSite withDeclaration(ResolvedMethodLikeDeclaration method) {
            var type=method.declaringType();
            boolean jar=type instanceof com.github.javaparser.symbolsolver.javassistmodel.JavassistClassDeclaration
                    ||type instanceof com.github.javaparser.symbolsolver.javassistmodel.JavassistInterfaceDeclaration
                    ||type instanceof com.github.javaparser.symbolsolver.javassistmodel.JavassistEnumDeclaration
                    ||type instanceof com.github.javaparser.symbolsolver.javassistmodel.JavassistAnnotationDeclaration
                    ||type instanceof com.github.javaparser.symbolsolver.javassistmodel.JavassistRecordDeclaration;
            String origin=jar?"JAR":type.toAst().isPresent()?"SOURCE":"RUNTIME";
            String pkg=method.getPackageName();
            String binary=(pkg.isBlank()?"":pkg+".")+method.getClassName().replace('.','$');
            return new CallSite(expression,startLine,endLine,callerClass,callerMethod,resolutionStatus,declaringType,signature,
                    parameterTypes,returnType,evidence+"; declarationOrigin="+origin+"; binaryClass="+binary,origin,binary);
        }
    }
    public record MethodSymbol(String declaringType, String signature, String kind) {}
    public record Result(boolean parseComplete, List<CallSite> callSites, String failureReason) {}
}
