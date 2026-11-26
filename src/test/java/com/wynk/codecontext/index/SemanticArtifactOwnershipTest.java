package com.wynk.codecontext.index;

import com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class SemanticArtifactOwnershipTest {
    @TempDir Path root;
    @Test void followsTheActualJarOrderRatherThanDependencyMetadataOrder() throws Exception {
        Path first=jar("first","package sample; public class Api { public String value(){return \"first\";} }");
        Path second=jar("second","package sample; public class Api { public Integer value(){return 2;} }");
        var cp=classpath(List.of(second,first),List.of(dependency(first,"first"),dependency(second,"second")));
        var call=call("Api",List.of(),cp);
        assertEquals("java.lang.Integer",call.returnType(),"real JavaParser resolution must choose the first classpath jar");
        assertEquals("fixture:second:1",SemanticArtifactOwnership.find(call,cp));
    }
    @Test void aSourceDeclarationShadowingAJarDoesNotBecomeAnExternalImpact() throws Exception {
        Path artifact=jar("external","package sample; public class Api { public String value(){return \"jar\";} }");
        Path sourceRoot=root.resolve("source");Files.createDirectories(sourceRoot.resolve("sample"));
        Files.writeString(sourceRoot.resolve("sample/Api.java"),"package sample; public class Api { public long value(){return 3L;} }");
        var cp=classpath(List.of(artifact),List.of(dependency(artifact,"external")));
        var call=call("Api",List.of(sourceRoot),cp);
        assertEquals("long",call.returnType(),"actual source solver must choose the repository-owned definition");
        assertNull(SemanticArtifactOwnership.find(call,cp));
    }
    @Test void nestedBinaryClassesUseTheirJvmNameForArtifactOwnership() throws Exception {
        Path artifact=jar("nested","package sample; public class Api { public static class Nested { public String value(){return \"nested\";} } }");
        var cp=classpath(List.of(artifact),List.of(dependency(artifact,"nested")));
        var call=call("Api.Nested",List.of(),cp);
        assertEquals("RESOLVED",call.resolutionStatus());assertEquals("sample.Api.Nested",call.declaringType());
        assertEquals("sample.Api$Nested",call.binaryClassName());assertEquals("JAR",call.declarationOrigin());
        assertEquals("fixture:nested:1",SemanticArtifactOwnership.find(call,cp));
    }
    @Test void runtimeDeclarationsCannotBeAttributedToAShadowJar() throws Exception {
        Path artifact=root.resolve("runtime-shadow.jar");
        try(var output=new JarOutputStream(Files.newOutputStream(artifact));var input=String.class.getResourceAsStream("String.class")) {
            assertNotNull(input);output.putNextEntry(new JarEntry("java/lang/String.class"));input.transferTo(output);output.closeEntry();
        }
        var cp=classpath(List.of(artifact),List.of(dependency(artifact,"shadow")));
        Path caller=root.resolve("Caller.java");Files.writeString(caller,"class Caller { String call(String value){return value.trim();} }");
        var call=JavaSemanticCallSiteAnalyzer.analyze(caller,List.of(),cp.jars()).callSites().getFirst();
        assertEquals("RESOLVED",call.resolutionStatus());assertEquals("RUNTIME",call.declarationOrigin());
        assertNull(SemanticArtifactOwnership.find(call,cp));
    }
    private JavaSemanticCallSiteAnalyzer.CallSite call(String type,List<Path> sources,MavenClasspathResolver.ModuleClasspath cp) throws Exception {
        Path caller=root.resolve("Caller.java");
        Files.writeString(caller,"class Caller { Object call(sample."+type+" api) { return api.value(); } }");
        var result=JavaSemanticCallSiteAnalyzer.analyze(caller,sources,cp.jars());
        assertTrue(result.parseComplete());
        var call=result.callSites().stream().filter(site->site.expression().equals("api.value()")).findFirst().orElseThrow();
        assertEquals("RESOLVED",call.resolutionStatus());return call;
    }
    private Path jar(String name,String source) throws Exception {
        Path directory=Files.createDirectories(root.resolve(name)),classes=Files.createDirectories(directory.resolve("classes"));
        Path java=directory.resolve("Api.java");Files.writeString(java,source);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),java.toString()));
        Path artifact=directory.resolve(name+".jar");
        try(var output=new JarOutputStream(Files.newOutputStream(artifact));var files=Files.walk(classes)) {
            for(Path file:files.filter(Files::isRegularFile).toList()) {
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));Files.copy(file,output);output.closeEntry();
            }
        }
        return artifact;
    }
    private static MavenClasspathResolver.ResolvedDependency dependency(Path jar,String name) {
        return new MavenClasspathResolver.ResolvedDependency("fixture",name,"1","","compile",false,false,jar,"hash");
    }
    private static MavenClasspathResolver.ModuleClasspath classpath(List<Path> jars,List<MavenClasspathResolver.ResolvedDependency> dependencies) {
        return new MavenClasspathResolver.ModuleClasspath("","fixture","consumer","1","jar",jars,"fingerprint","RESOLVED",null,dependencies);
    }
}
