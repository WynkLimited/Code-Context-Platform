package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class MavenClasspathResolverTest {
    @TempDir Path temp;

    @Test void resolvesExternalJarFromApprovedFileRepositoryWithoutRunningBuildPlugins() throws Exception {
        Path repo=temp.resolve("repo"); Path artifact=repo.resolve("org/acme/money/1.0/money-1.0.jar"); Files.createDirectories(artifact.getParent());
        Path source=temp.resolve("Money.java"); Files.writeString(source,"package org.acme; public class Money { public String convert(String x){ return x; } }");
        Path classes=temp.resolve("classes"); Files.createDirectories(classes);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),source.toString()));
        try(var out=new JarOutputStream(Files.newOutputStream(artifact));var stream=Files.walk(classes)){
            for(Path c:stream.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(classes.relativize(c).toString().replace('\\','/')));Files.copy(c,out);out.closeEntry();}
        }
        Files.writeString(repo.resolve("org/acme/money/1.0/money-1.0.pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>money</artifactId><version>1.0</version></project>");
        Path root=temp.resolve("snapshot");Path module=root.resolve("consumer");Files.createDirectories(module.resolve("src/main/java/app"));
        Files.writeString(module.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>consumer</artifactId><version>1</version>
                <repositories><repository><id>fixture</id><url>%s</url></repository></repositories>
                <dependencies><dependency><groupId>org.acme</groupId><artifactId>money</artifactId><version>1.0</version></dependency></dependencies>
                </project>
                """.formatted(repo.toUri()));
        Files.writeString(module.resolve("src/main/java/app/Payment.java"),"package app; import org.acme.Money; class Payment { String run(Money m){return m.convert(\"x\");} }");
        var result=new MavenClasspathResolver().resolve(root,"commit-1",temp.resolve("cache"),List.of(new MavenClasspathResolver.RepositorySpec("fixture",repo.toUri().toString(),Set.of())));
        assertTrue(result.complete(),result.reason());
        var cp=result.modules().stream().filter(m->m.modulePath().equals("consumer")).findFirst().orElseThrow();
        assertTrue(cp.jars().stream().anyMatch(p->p.getFileName().toString().equals("money-1.0.jar")),cp.jars().toString());
        var analysis=com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.analyze(module.resolve("src/main/java/app/Payment.java"),List.of(module.resolve("src/main/java")),cp.jars());
        var convert=analysis.callSites().stream().filter(c->c.expression().contains("convert")).findFirst().orElseThrow();
        assertEquals("RESOLVED",convert.resolutionStatus());
        assertEquals("org.acme.Money",convert.declaringType());
        assertEquals("convert(java.lang.String)",convert.signature());
        var providerMethods=com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.declaredMethods(source,List.of(),List.of());
        assertTrue(providerMethods.stream().anyMatch(m->(m.declaringType()+"#"+m.signature()).equals("org.acme.Money#convert(java.lang.String)")));
        assertEquals("app.Payment",convert.callerClass());
        assertEquals("run(org.acme.Money)",convert.callerMethod());
        // These are the exact V5 symbol keys used by semantic_call_edges.target_symbol_id
        // and caller_symbol_id, tying the real Maven classpath binding to the graph identity.
        assertEquals("org.acme.Money#convert(java.lang.String)",convert.declaringType()+"#"+convert.signature());
        assertEquals("app.Payment#run(org.acme.Money)",convert.callerClass()+"#"+convert.callerMethod());
    }
}
