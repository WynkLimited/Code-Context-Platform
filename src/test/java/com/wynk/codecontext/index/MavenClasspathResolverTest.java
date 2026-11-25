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
        Path repo=temp.resolve("repo"); Path artifact=repo.resolve("org/acme/common-library/1.0/common-library-1.0.jar"); Files.createDirectories(artifact.getParent());
        Path source=temp.resolve("Money.java"); Files.writeString(source,"package org.acme; public class Money { public String convert(String x){ return x; } public String convert(Integer x){ return x.toString(); } }");
        Path classes=temp.resolve("classes"); Files.createDirectories(classes);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),source.toString()));
        try(var out=new JarOutputStream(Files.newOutputStream(artifact));var stream=Files.walk(classes)){
            for(Path c:stream.filter(Files::isRegularFile).toList()){out.putNextEntry(new JarEntry(classes.relativize(c).toString().replace('\\','/')));Files.copy(c,out);out.closeEntry();}
        }
        Path pom=repo.resolve("org/acme/common-library/1.0/common-library-1.0.pom");
        Files.writeString(pom,"<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>common-library</artifactId><version>1.0</version></project>");
        writeChecksumSidecars(artifact);
        writeChecksumSidecars(pom);
        Path root=temp.resolve("snapshot");Path module=root;Files.createDirectories(module.resolve("src/main/java/app"));
        Files.writeString(module.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>consumer</artifactId><version>1</version>
                <repositories><repository><id>fixture</id><url>%s</url></repository></repositories>
                <dependencies><dependency><groupId>org.acme</groupId><artifactId>common-library</artifactId><version>1.0</version></dependency></dependencies>
                </project>
                """.formatted(repo.toUri()));
        Files.writeString(module.resolve("src/main/java/app/ConsumerA.java"),"package app; import org.acme.Money; public class ConsumerA { public String call(Money money) { return money.convert(\"x\"); } }");
        var result=new MavenClasspathResolver().resolve(root,"commit-1",temp.resolve("cache"),List.of(new MavenClasspathResolver.RepositorySpec("fixture",repo.toUri().toString(),Set.of())));
        assertTrue(result.complete(),result.reason());
        var cp=result.modules().stream().filter(m->m.modulePath().isBlank()).findFirst().orElseThrow();
        assertTrue(cp.jars().stream().anyMatch(p->p.getFileName().toString().equals("common-library-1.0.jar")),cp.jars().toString());
        var analysis=com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.analyze(module.resolve("src/main/java/app/ConsumerA.java"),List.of(module.resolve("src/main/java")),cp.jars());
        var convert=analysis.callSites().stream().filter(c->c.expression().contains("convert")).findFirst().orElseThrow();
        assertEquals("RESOLVED",convert.resolutionStatus());
        assertEquals("org.acme.Money",convert.declaringType());
        assertEquals("convert(java.lang.String)",convert.signature());
        var providerMethods=com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.declaredMethods(source,List.of(),List.of());
        assertTrue(providerMethods.stream().anyMatch(m->(m.declaringType()+"#"+m.signature()).equals("org.acme.Money#convert(java.lang.String)")));
        assertEquals("app.ConsumerA",convert.callerClass());
        assertEquals("call(org.acme.Money)",convert.callerMethod());
        // These are the exact V5 symbol keys used by semantic_call_edges.target_symbol_id
        // and caller_symbol_id, tying the real Maven classpath binding to the graph identity.
        assertEquals("org.acme.Money#convert(java.lang.String)",convert.declaringType()+"#"+convert.signature());
        assertEquals("app.ConsumerA#call(org.acme.Money)",convert.callerClass()+"#"+convert.callerMethod());
    }

    @Test void rejectsHttpsMavenRepositoryThatResolvesToLoopbackOrMetadataAddress() {
        // RFC1918 targets remain allowed for corporate Maven hosts; NetworkPolicy is the
        // remaining private-egress control. Loopback and link-local/metadata must still fail.
        var loopback = new MavenClasspathResolver.RepositorySpec(
                "loop", "https://artifacts.example/maven/", Set.of("artifacts.example"));
        var loopFailure = assertThrows(IllegalArgumentException.class,
                () -> loopback.remote(host -> new java.net.InetAddress[]{
                        java.net.InetAddress.getByName("127.0.0.1")}));
        assertTrue(loopFailure.getMessage().toLowerCase().contains("not approved"), loopFailure.getMessage());

        var metadata = new MavenClasspathResolver.RepositorySpec(
                "meta", "https://artifacts.example/maven/", Set.of("artifacts.example"));
        var metaFailure = assertThrows(IllegalArgumentException.class,
                () -> metadata.remote(host -> new java.net.InetAddress[]{
                        java.net.InetAddress.getByName("169.254.169.254")}));
        assertTrue(metaFailure.getMessage().toLowerCase().contains("not approved"), metaFailure.getMessage());
    }

    @Test void rejectsNonHttpsMavenRepositoryUrls() {
        var http = new MavenClasspathResolver.RepositorySpec(
                "insecure", "http://repo.example/maven/", Set.of("repo.example"));
        var failure = assertThrows(IllegalArgumentException.class, http::remote);
        assertTrue(failure.getMessage().toLowerCase().contains("not approved"), failure.getMessage());
    }

    private static void writeChecksumSidecars(Path artifact) throws Exception {
        byte[] content=Files.readAllBytes(artifact);
        for(String algorithm:List.of("SHA-1","MD5")) {
            String extension=algorithm.equals("SHA-1")?"sha1":"md5";
            String digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algorithm).digest(content));
            Files.writeString(Path.of(artifact+"."+extension),digest+"\n");
        }
    }
}
