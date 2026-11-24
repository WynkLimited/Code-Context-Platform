package com.wynk.codecontext.port.contract.extract;

import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MavenBuildContractTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary;
    private static final String PARENT = "<project><groupId>p</groupId><artifactId>parent</artifactId><version>1</version>"
            + "<properties><dep.version>2</dep.version></properties><dependencyManagement><dependencies>"
            + "<dependency><groupId>d</groupId><artifactId>lib</artifactId><version>${dep.version}</version></dependency>"
            + "</dependencies></dependencyManagement></project>";
    private static final String CHILD = "<project><parent><groupId>p</groupId><artifactId>parent</artifactId><version>1</version><relativePath/></parent>"
            + "<artifactId>consumer</artifactId><dependencies><dependency><groupId>d</groupId><artifactId>lib</artifactId>"
            + "</dependency></dependencies></project>";
    @Test void removedManagementIsModelEvidenceNotAnInventedCompileBreak() {
        String after = PARENT.replace("<dependencyManagement>" + PARENT.split("<dependencyManagement>")[1], "</project>");
        var assessment = MavenBuildContract.assess(Map.of("pom.xml", CHILD), Map.of("pom.xml", PARENT), Map.of("pom.xml", after));
        assertTrue(assessment.changes().stream().anyMatch(s -> s.contains("d:lib") && s.contains("2 -> UNMANAGED")), assessment.toString());
        assertFalse(assessment.classpathVerified());
    }
    @Test void explicitVersionOverridesParentManagement() {
        var assessment = MavenBuildContract.assess(Map.of("pom.xml", CHILD.replace("</artifactId></dependency>", "</artifactId><version>7</version></dependency>")),
                Map.of("parent/pom.xml", PARENT), Map.of("parent/pom.xml", PARENT.replace(">${dep.version}<", ">3<")));
        assertTrue(assessment.changes().isEmpty(), assessment.toString());
    }
    @Test void importedBomUnresolvedPropertiesAndDoctypeRemainUnavailable() {
        String bom = PARENT.replace("<version>${dep.version}</version>", "<version>${missing}</version><type>pom</type><scope>import</scope>");
        var assessment = MavenBuildContract.assess(Map.of("pom.xml", CHILD), Map.of("pom.xml", bom), Map.of("pom.xml", bom));
        assertFalse(assessment.limitations().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> MavenBuildContract.signature("<!DOCTYPE project SYSTEM 'file:///etc/passwd'><project/>"));
    }
    @Test void propertyVersionChangesInvalidateBuildContract() {
        assertNotEquals(MavenBuildContract.signature(PARENT), MavenBuildContract.signature(PARENT.replace(">2<", ">3<")));
    }
    @Test void managementChangesRouteAsUnknownBuildContractsAndParentsAreIndexed() throws Exception {
        var before = temporary.resolve("before"); var after = temporary.resolve("after");
        java.nio.file.Files.createDirectories(before); java.nio.file.Files.createDirectories(after);
        java.nio.file.Files.writeString(before.resolve("pom.xml"), PARENT);
        java.nio.file.Files.writeString(after.resolve("pom.xml"), PARENT.replace(">2<", ">3<"));
        var extractor = new MavenContractExtractor();
        var baseline = new com.wynk.codecontext.port.contract.RepositoryContext("org", "lib", "a".repeat(40), "main", before);
        var head = new com.wynk.codecontext.port.contract.RepositoryContext("org", "lib", "b".repeat(40), "main", after);
        var changes = new com.wynk.codecontext.port.contract.compat.ContractCompatibilityEngine().compare(
                extractor.extractExports(baseline), extractor.extractExports(head));
        assertEquals(1, changes.size());
        assertEquals(com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChangeType.BUILD_DEPENDENCY_CHANGED,
                changes.get(0).changeType());
        assertFalse(changes.get(0).isBreaking());
        java.nio.file.Files.writeString(after.resolve("pom.xml"), CHILD);
        assertTrue(extractor.extractImports(head).stream().anyMatch(i -> "parent".equals(i.metadata().get("role"))));
    }
}
