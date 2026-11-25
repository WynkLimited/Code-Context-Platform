package com.wynk.codecontext.index;

import com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Actual allowlisted HTTPS artifacts: no build process, local Maven settings or installed parent required. */
class MavenRemoteModelIT {
    @TempDir Path root;
    @Test void resolvesRemoteParentAndBomAndBindsCallsAgainstTheDownloadedJars() throws Exception {
        Path snapshot=root.resolve("snapshot");Path source=snapshot.resolve("src/main/java/app/Consumer.java");
        Files.createDirectories(source.getParent());
        Files.writeString(snapshot.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion>
                  <parent><groupId>org.apache</groupId><artifactId>apache</artifactId><version>35</version><relativePath/></parent>
                  <groupId>fixture</groupId><artifactId>remote-model-consumer</artifactId><version>1</version>
                  <dependencyManagement><dependencies><dependency><groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>5.11.4</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
                  <dependencies>
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId><version>3.17.0</version></dependency>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter-api</artifactId></dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(source,"""
                package app;
                public class Consumer {
                  public boolean call(String value) {
                    org.junit.jupiter.api.Assertions.assertNotNull(value);
                    return org.apache.commons.lang3.StringUtils.isBlank(value);
                  }
                }
                """);
        var approved=List.of(new MavenClasspathResolver.RepositorySpec("central","https://repo.maven.apache.org/maven2",Set.of("repo.maven.apache.org")));
        var result=new MavenClasspathResolver().resolve(snapshot,"a".repeat(40),root.resolve("empty-cache"),approved);
        assertTrue(result.complete(),result.reason());assertEquals(1,result.modules().size());
        var module=result.modules().getFirst();
        assertTrue(module.dependencies().stream().anyMatch(dep->dep.groupId().equals("org.junit.jupiter")&&dep.artifactId().equals("junit-jupiter-api")&&dep.version().equals("5.11.4")),module.dependencies().toString());
        assertTrue(module.dependencies().stream().anyMatch(dep->dep.artifactId().equals("opentest4j")&&dep.transitive()),module.dependencies().toString());
        var calls=JavaSemanticCallSiteAnalyzer.analyze(source,List.of(snapshot.resolve("src/main/java")),module.jars()).callSites();
        assertEquals(2,calls.size());assertTrue(calls.stream().allMatch(call->call.resolutionStatus().equals("RESOLVED")),calls.toString());
        assertEquals(Set.of("org.junit.jupiter.api.Assertions#assertNotNull(java.lang.Object)","org.apache.commons.lang3.StringUtils#isBlank(java.lang.CharSequence)"),
                calls.stream().map(call->call.declaringType()+"#"+call.signature()).collect(java.util.stream.Collectors.toSet()));
    }
}
