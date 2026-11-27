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

    @Test void resolvesParentAndBomAcrossReactorModulesWithoutRetainingModelsBetweenCommits() throws Exception {
        Path repository=temp.resolve("reactor-artifacts");
        for(String version:List.of("1.0","2.0")) {
            Path artifact=repository.resolve("org/acme/common-library/"+version);
            Files.createDirectories(artifact);
            Path jar=artifact.resolve("common-library-"+version+".jar");
            try(var output=new JarOutputStream(Files.newOutputStream(jar))) { }
            Path pom=artifact.resolve("common-library-"+version+".pom");
            Files.writeString(pom,"<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>common-library</artifactId><version>"+version+"</version></project>");
            writeChecksumSidecars(jar);writeChecksumSidecars(pom);
            Path bom=repository.resolve("org/acme/platform-bom/"+version+"/platform-bom-"+version+".pom");
            Files.createDirectories(bom.getParent());
            Files.writeString(bom,"""
                    <project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>platform-bom</artifactId><version>%s</version><packaging>pom</packaging>
                    <dependencyManagement><dependencies><dependency><groupId>org.acme</groupId><artifactId>common-library</artifactId><version>%s</version></dependency></dependencies></dependencyManagement></project>
                    """.formatted(version,version));
            writeChecksumSidecars(bom);
        }
        Path snapshot=temp.resolve("reactor-snapshot");Files.createDirectories(snapshot);
        String parent="""
                <project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging>
                <properties><bom.version>1.0</bom.version></properties>
                <dependencyManagement><dependencies><dependency><groupId>org.acme</groupId><artifactId>platform-bom</artifactId><version>${bom.version}</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
                <build><plugins><plugin><groupId>unavailable.plugin</groupId><artifactId>must-not-run</artifactId><version>1</version></plugin></plugins></build></project>
                """;
        Files.writeString(snapshot.resolve("pom.xml"),parent);
        for(int module=0;module<12;module++) {
            Path child=snapshot.resolve("module-"+module);Files.createDirectories(child);
            Files.writeString(child.resolve("pom.xml"),"""
                    <project><modelVersion>4.0.0</modelVersion><parent><groupId>app</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>module-%s</artifactId>
                    <dependencies><dependency><groupId>org.acme</groupId><artifactId>common-library</artifactId></dependency></dependencies></project>
                    """.formatted(module));
        }
        var resolver=new MavenClasspathResolver();
        var approved=List.of(new MavenClasspathResolver.RepositorySpec("fixture",repository.toUri().toString(),Set.of()));
        for(String version:List.of("1.0","2.0")) {
            Files.writeString(snapshot.resolve("pom.xml"),parent.replace("<bom.version>1.0</bom.version>","<bom.version>"+version+"</bom.version>"));
            var resolved=resolver.resolve(snapshot,"commit-"+version,temp.resolve("reactor-cache"),approved);
            assertTrue(resolved.complete(),resolved.reason());assertEquals(13,resolved.modules().size());
            assertEquals("commit-"+version,resolved.commit());
            for(var module:resolved.modules()) {
                assertEquals("app",module.groupId());assertEquals("1",module.version());
                if(module.modulePath().isBlank()) { assertTrue(module.jars().isEmpty());continue; }
                assertEquals(1,module.jars().size());
                assertTrue(module.jars().getFirst().getFileName().toString().equals("common-library-"+version+".jar"));
                assertEquals(version,module.dependencies().getFirst().version());
            }
        }
    }

    @Test void rejectsRepositoriesInjectedByTransitiveArtifactDescriptors() throws Exception {
        Path approved=temp.resolve("approved-artifacts"),unapproved=temp.resolve("unapproved-artifacts");
        Files.createDirectories(approved);Files.createDirectories(unapproved);
        installDescriptorArtifact(unapproved,"hidden","<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>hidden</artifactId><version>1</version></project>");
        installDescriptorArtifact(approved,"entry","""
                <project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>entry</artifactId><version>1</version>
                <repositories><repository><id>injected</id><url>%s</url></repository></repositories>
                <dependencies><dependency><groupId>org.acme</groupId><artifactId>hidden</artifactId><version>1</version></dependency></dependencies></project>
                """.formatted(unapproved.toUri()));
        Path snapshot=temp.resolve("injection-snapshot");Files.createDirectories(snapshot);
        Files.writeString(snapshot.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>consumer</artifactId><version>1</version>
                <dependencies><dependency><groupId>org.acme</groupId><artifactId>entry</artifactId><version>1</version></dependency></dependencies></project>
                """);
        Path cache=temp.resolve("injection-cache");
        var resolved=new MavenClasspathResolver().resolve(snapshot,"commit-injection",cache,
                List.of(new MavenClasspathResolver.RepositorySpec("approved",approved.toUri().toString(),Set.of())));
        assertFalse(resolved.complete(), "unapproved descriptor repositories must not supply transitive artifacts");
        if(Files.exists(cache))try(var cached=Files.walk(cache)) {
            assertFalse(cached.anyMatch(path->path.getFileName().toString().equals("hidden-1.jar")), "unapproved artifact must never enter any policy cache");
        }
    }
    private static void installDescriptorArtifact(Path repository,String artifact,String model) throws Exception {
        Path directory=repository.resolve("org/acme/"+artifact+"/1");Files.createDirectories(directory);
        Path pom=directory.resolve(artifact+"-1.pom"),jar=directory.resolve(artifact+"-1.jar");
        Files.writeString(pom,model);try(var output=new JarOutputStream(Files.newOutputStream(jar))) { }
        writeChecksumSidecars(pom);writeChecksumSidecars(jar);
    }

    @Test void rejectsParentRelativePathOutsideTheImmutableSnapshot() throws Exception {
        Path outside=temp.resolve("outside-parent");Files.createDirectories(outside);
        Files.writeString(outside.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>outside</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging></project>");
        Path snapshot=temp.resolve("parent-escape-snapshot");Files.createDirectories(snapshot);
        Files.writeString(snapshot.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><parent><groupId>outside</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../outside-parent/pom.xml</relativePath></parent><artifactId>child</artifactId></project>
                """);
        var resolved=new MavenClasspathResolver().resolve(snapshot,"commit-parent-escape",temp.resolve("parent-cache"),List.of());
        assertFalse(resolved.complete(), "parent outside the captured snapshot must not be consumed");
    }
    @Test void rejectsSymlinkedBomInAnApprovedFileRepository() throws Exception {
        Path outside=temp.resolve("outside-bom.xml");
        Files.writeString(outside,"<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>bom</artifactId><version>1</version><packaging>pom</packaging></project>");
        Path approved=temp.resolve("bom-approved");Path bom=approved.resolve("org/acme/bom/1/bom-1.pom");
        Files.createDirectories(bom.getParent());Files.createSymbolicLink(bom,outside);
        Path snapshot=temp.resolve("bom-symlink-snapshot");Files.createDirectories(snapshot);
        Files.writeString(snapshot.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>child</artifactId><version>1</version>
                <dependencyManagement><dependencies><dependency><groupId>org.acme</groupId><artifactId>bom</artifactId><version>1</version><scope>import</scope><type>pom</type></dependency></dependencies></dependencyManagement></project>
                """);
        var resolved=new MavenClasspathResolver().resolve(snapshot,"commit-bom-symlink",temp.resolve("bom-cache"),
                List.of(new MavenClasspathResolver.RepositorySpec("approved",approved.toUri().toString(),Set.of())));
        assertFalse(resolved.complete(), "symlinked approved model must not read an unapproved file");
    }

    @Test void rejectsDoctypeAndExcessiveModelNestingWithoutReadingExternalEntities() throws Exception {
        Path secret=temp.resolve("external-entity.txt");Files.writeString(secret,"PRIVATE_SENTINEL_MUST_NOT_BE_READ");
        Path snapshot=temp.resolve("xml-snapshot");Files.createDirectories(snapshot);
        String doctype="<!DOCTYPE project [<!ENTITY secret SYSTEM '"+secret.toUri()+"'>]>";
        String project="<project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>consumer</artifactId><version>1</version></project>";
        for(String model:List.of(doctype+project.replace("consumer","&secret;"),
                project.replace("</project>","<properties>"+"<nested>".repeat(150)+"value"+"</nested>".repeat(150)+"</properties></project>"))) {
            Files.writeString(snapshot.resolve("pom.xml"),model);
            var resolved=new MavenClasspathResolver().resolve(snapshot,"commit-xml",temp.resolve("xml-cache"),List.of());
            assertFalse(resolved.complete());
            assertFalse(resolved.reason().contains("PRIVATE_SENTINEL_MUST_NOT_BE_READ"));
        }
    }

    @Test void revokedRepositoryUrlCannotRemainTrustedThroughTheSharedLocalCache() throws Exception {
        Path first=temp.resolve("original-approved"),replacement=temp.resolve("replacement-approved");
        Files.createDirectories(first);Files.createDirectories(replacement);
        installDescriptorArtifact(first,"entry","<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>entry</artifactId><version>1</version></project>");
        Path snapshot=temp.resolve("revocation-snapshot");Files.createDirectories(snapshot);
        Files.writeString(snapshot.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>consumer</artifactId><version>1</version>
                <dependencies><dependency><groupId>org.acme</groupId><artifactId>entry</artifactId><version>1</version></dependency></dependencies></project>
                """);
        var resolver=new MavenClasspathResolver();Path cache=temp.resolve("shared-policy-cache");
        assertTrue(resolver.resolve(snapshot,"commit-before-revocation",cache,
                List.of(new MavenClasspathResolver.RepositorySpec("same-id",first.toUri().toString(),Set.of()))).complete());
        var revoked=resolver.resolve(snapshot,"commit-after-revocation",cache,
                List.of(new MavenClasspathResolver.RepositorySpec("same-id",replacement.toUri().toString(),Set.of())));
        assertFalse(revoked.complete(), "an artifact from the revoked URL must not be trusted merely because repository id matches");
    }

    @Test void doesNotInterpolatePrivateJvmPropertiesIntoUntrustedModels() throws Exception {
        String key="ccp.synthetic.secret";String previous=System.getProperty(key);
        try {
            System.setProperty(key,"PRIVATE_PROPERTY_MUST_NOT_BE_EXPOSED");
            Path snapshot=temp.resolve("property-snapshot");Files.createDirectories(snapshot);
            Files.writeString(snapshot.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>consumer</artifactId><version>${ccp.synthetic.secret}</version></project>");
            var result=new MavenClasspathResolver().resolve(snapshot,"commit-property",temp.resolve("property-cache"),List.of());
            assertFalse(result.complete(),"unresolved module coordinates must remain incomplete");
            assertFalse(result.toString().contains("PRIVATE_PROPERTY_MUST_NOT_BE_EXPOSED"));
        } finally {if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);}
    }

    @Test void rejectsCorruptOrMissingArtifactChecksums() throws Exception {
        for(String mode:List.of("corrupt","missing")) {
            Path repository=temp.resolve("checksum-"+mode);Files.createDirectories(repository);
            installDescriptorArtifact(repository,"entry","<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId><artifactId>entry</artifactId><version>1</version></project>");
            Path jar=repository.resolve("org/acme/entry/1/entry-1.jar");
            for(String algorithm:List.of("sha1","md5")) {
                Path sidecar=Path.of(jar+"."+algorithm);
                if(mode.equals("missing"))Files.delete(sidecar);else Files.writeString(sidecar,"0".repeat(algorithm.equals("sha1")?40:32));
            }
            Path snapshot=temp.resolve("checksum-snapshot-"+mode);Files.createDirectories(snapshot);
            Files.writeString(snapshot.resolve("pom.xml"),"""
                    <project><modelVersion>4.0.0</modelVersion><groupId>app</groupId><artifactId>consumer</artifactId><version>1</version>
                    <dependencies><dependency><groupId>org.acme</groupId><artifactId>entry</artifactId><version>1</version></dependency></dependencies></project>
                    """);
            var result=new MavenClasspathResolver().resolve(snapshot,"commit-checksum",temp.resolve("checksum-cache-"+mode),
                    List.of(new MavenClasspathResolver.RepositorySpec("fixture",repository.toUri().toString(),Set.of())));
            assertFalse(result.complete(), mode+" checksum must fail resolution rather than warn and accept the artifact");
            var retry=new MavenClasspathResolver().resolve(snapshot,"commit-checksum-retry",temp.resolve("checksum-cache-"+mode),
                    List.of(new MavenClasspathResolver.RepositorySpec("fixture",repository.toUri().toString(),Set.of())));
            assertFalse(retry.complete(),"failed checksum must not become trusted cache evidence on retry");
        }
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
