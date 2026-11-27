package com.wynk.codecontext.port.contract.source;

import org.springframework.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.io.*;
import static com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.*;

/** Compiler-derived JVM evidence. Does not execute repository builds, plugins or compiled code. */
public final class KotlinJvmSemanticAnalyzer {
    private static final java.util.concurrent.Semaphore COMPILATIONS = new java.util.concurrent.Semaphore(2, true);
    private final String compiler;
    public KotlinJvmSemanticAnalyzer() { this(System.getenv().getOrDefault("KOTLIN_COMPILER","kotlinc")); }
    public KotlinJvmSemanticAnalyzer(String compiler) { this.compiler=compiler; }
    public record Declaration(String file,MethodSymbol symbol) {}
    public record Invocation(String file,CallSite call) {}
    public record Result(boolean complete,Path jar,List<Declaration> declarations,List<Invocation> calls,String diagnostic) {}

    public Result analyze(Path snapshot,List<Path> sources,List<Path> dependencies,Path output) throws Exception {
        if(sources.stream().noneMatch(p->p.toString().endsWith(".kt")))return new Result(true,null,List.of(),List.of(),"");
        if(sources.size()>5000)return incomplete("KOTLIN_MODULE_SOURCE_LIMIT");
        Path trusted=snapshot.toRealPath();
        for(Path source:sources) {
            if(!source.toRealPath().startsWith(trusted)||Files.isSymbolicLink(source)||Files.size(source)>2*1024*1024)
                throw new IllegalArgumentException("source is outside snapshot or exceeds compiler input limit");
        }
        Files.createDirectories(output);
        if(!output.toRealPath().startsWith(trusted))throw new IllegalArgumentException("compiler output escaped snapshot");
        for(Path dependency:dependencies) {
            if(!Files.isRegularFile(dependency)||Files.isSymbolicLink(dependency)||!dependency.toString().endsWith(".jar"))
                throw new IllegalArgumentException("compiler classpath must contain approved regular jars");
        }
        Path jar=output.resolve("kotlin-analysis.jar");
        var command=new ArrayList<String>();command.add(compiler);
        for(Path source:sources) {
            if(!source.toAbsolutePath().normalize().startsWith(snapshot.toAbsolutePath().normalize())||Files.isSymbolicLink(source))
                throw new IllegalArgumentException("source escaped immutable snapshot");
            if(source.toString().endsWith(".java")||source.toString().endsWith(".kt"))command.add(source.toAbsolutePath().toString());
        }
        command.addAll(List.of("-jvm-target","21","-d",jar.toAbsolutePath().toString()));
        if(!dependencies.isEmpty())command.addAll(List.of("-classpath",String.join(File.pathSeparator,dependencies.stream().map(p->p.toAbsolutePath().toString()).toList())));
        if(!COMPILATIONS.tryAcquire(30,TimeUnit.SECONDS))return incomplete("KOTLIN_COMPILER_CAPACITY_EXCEEDED");
        try { return compile(snapshot,sources,jar,command); }
        finally { COMPILATIONS.release(); }
    }

    private Result compile(Path snapshot,List<Path> sources,Path jar,List<String> command) throws Exception {
        Process process;
        try {
            var builder=new ProcessBuilder(command).redirectErrorStream(true);
            configureEnvironment(builder.environment());
            builder.directory(snapshot.toFile());
            process=builder.start();
        } catch(IOException unavailable) { return incomplete("KOTLIN_COMPILER_UNAVAILABLE"); }
        var diagnostic=new ByteArrayOutputStream();
        Thread drain=Thread.startVirtualThread(()->{
            try(var input=process.getInputStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=input.read(buffer))!=-1) if(diagnostic.size()<8192)diagnostic.write(buffer,0,Math.min(n,8192-diagnostic.size()));
            }catch(IOException ignored){}
        });
        try {
            if(!process.waitFor(120,TimeUnit.SECONDS))return incomplete("KOTLIN_COMPILER_TIMEOUT");
            drain.join(2000);
            if(process.exitValue()!=0)return incomplete("KOTLIN_COMPILATION_FAILED: compiler rejected source or classpath; raw diagnostics withheld");
        } finally {
            if(process.isAlive()) {
                try { process.descendants().forEach(p->p.destroyForcibly()); }
                catch(RuntimeException denied) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Kotlin compiler descendant cleanup unavailable: {}",denied.getClass().getSimpleName()); }
                finally { process.destroyForcibly(); }
            }
        }
        if(Files.size(jar)>256L*1024*1024)return incomplete("KOTLIN_OUTPUT_SIZE_LIMIT");
        var files=new HashMap<String,List<String>>();
        for(Path source:sources)if(source.toString().endsWith(".kt")) {
            String text=Files.readString(source);
            var pkg=java.util.regex.Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)").matcher(text);
            String key=(pkg.find()?pkg.group(1).replace('.','/')+"/":"")+source.getFileName();
            files.computeIfAbsent(key,k->new ArrayList<>()).add(snapshot.relativize(source).toString().replace(File.separatorChar,'/'));
        }
        var declarations=new ArrayList<Declaration>();var calls=new ArrayList<Invocation>();
        try(var archive=new JarFile(jar.toFile())) {
            for(var entry:Collections.list(archive.entries()))if(entry.getName().endsWith(".class")) {
                try(var input=archive.getInputStream(entry)) {
                    byte[] bytes=input.readNBytes(10*1024*1024+1);
                    if(bytes.length>10*1024*1024)return incomplete("KOTLIN_CLASS_SIZE_LIMIT");
                    new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                        String owner,file;
                        @Override public void visit(int v,int a,String name,String sig,String parent,String[] interfaces){owner=name;}
                        @Override public void visitSource(String source,String debug){
                            int slash=owner.lastIndexOf('/');
                            var matches=files.getOrDefault((slash<0?"":owner.substring(0,slash+1))+source,List.of());
                            if(matches.size()==1)file=matches.get(0);
                        }
                        @Override public MethodVisitor visitMethod(int access,String name,String descriptor,String generic,String[] exceptions) {
                            if(file==null||name.equals("<clinit>")||(access&(Opcodes.ACC_SYNTHETIC|Opcodes.ACC_BRIDGE))!=0)return null;
                            String callerOwner=owner.replace('/','.');
                            String caller=signature(callerOwner,name,descriptor);
                            declarations.add(new Declaration(file,new MethodSymbol(callerOwner,caller,name.equals("<init>")?"CONSTRUCTOR":"METHOD")));
                            return new MethodVisitor(Opcodes.ASM9) {
                                int line;
                                @Override public void visitLineNumber(int value,Label start){line=value;}
                                @Override public void visitMethodInsn(int op,String target,String method,String desc,boolean itf) {
                                    if(line<=0||target.startsWith("kotlin/jvm/internal/")||method.contains("$default"))return;
                                    String type=target.replace('/','.');String resolved=signature(type,method,desc);
                                    calls.add(new Invocation(file,new CallSite(type+"#"+resolved,line,line,callerOwner,caller,"RESOLVED",type,resolved,
                                            Arrays.stream(Type.getArgumentTypes(desc)).map(Type::getClassName).toList(),Type.getReturnType(desc).getClassName(),"KOTLIN_COMPILER_JVM_BYTECODE; source attribution uses line table; inline calls may be absent")));
                                }
                            };
                        }
                    },ClassReader.SKIP_FRAMES);
                }
            }
        }
        return new Result(true,jar,List.copyOf(declarations),List.copyOf(calls),"JVM bytecode evidence; inline calls and source-only types are not reconstructed");
    }
    static void configureEnvironment(Map<String,String> environment) {
        // A compiler JVM must never inherit credentials or JVM injection options from the app.
        com.wynk.codecontext.security.SubprocessHardening.scrubEnvironment(environment);
        var allowed=new HashMap<String,String>();
        for(String key:List.of("PATH","JAVA_HOME","SystemRoot","TMPDIR","TMP","TEMP"))
            if(environment.containsKey(key))allowed.put(key,environment.get(key));
        environment.clear(); environment.putAll(allowed);
        environment.put("JAVA_OPTS","-Xmx512m -Dfile.encoding=UTF-8");
        environment.put("LANG","C.UTF-8");
    }

    private static String signature(String owner,String name,String descriptor) {
        return (name.equals("<init>")?owner.substring(owner.lastIndexOf('.')+1):name)+"("+String.join(",",Arrays.stream(Type.getArgumentTypes(descriptor)).map(Type::getClassName).toList())+")";
    }
    private static Result incomplete(String reason){return new Result(false,null,List.of(),List.of(),reason);}
}
