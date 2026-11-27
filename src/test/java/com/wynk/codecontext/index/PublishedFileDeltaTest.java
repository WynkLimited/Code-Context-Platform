package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PublishedFileDeltaTest {
    @Test void detectsAddedModifiedDeletedAndUniqueExactRenamesDeterministically() {
        var before=Map.of("old/A.java","a","B.java","b","deleted.py","d","stable.ts","s");
        var after=Map.of("new/A.java","a","B.java","changed","added.kt","k","stable.ts","s");
        var delta=PublishedFileDelta.between(before,after);
        assertEquals(List.of("added.kt"),delta.added());assertEquals(List.of("B.java"),delta.modified());
        assertEquals(List.of("deleted.py"),delta.deleted());assertEquals(List.of(new PublishedFileDelta.Move("old/A.java","new/A.java")),delta.renamed());
        assertEquals(List.of("B.java","added.kt","deleted.py","new/A.java","old/A.java"),delta.affectedPaths());assertFalse(delta.globalInputsChanged());
    }
    @Test void neverInventsRenameIdentityForAmbiguousIdenticalFiles() {
        var delta=PublishedFileDelta.between(Map.of("a.py","same","b.py","same"),Map.of("c.py","same"));
        assertEquals(List.of(),delta.renamed());assertEquals(List.of("a.py","b.py"),delta.deleted());assertEquals(List.of("c.py"),delta.added());
    }
    @Test void treatsEditedMovesAndUnrelatedForcePushContentsAsConservativeAddDeleteChanges() {
        var delta=PublishedFileDelta.between(Map.of("old/A.java","before","stable.py","same"),Map.of("new/A.java","after","stable.py","same"));
        assertEquals(List.of("new/A.java"),delta.added());assertEquals(List.of("old/A.java"),delta.deleted());assertTrue(delta.renamed().isEmpty());
        assertTrue(PublishedFileDelta.between(Map.of("stable.py","same"),Map.of("stable.py","same")).empty());
    }
    @Test void invalidatesGlobalExtractionForBuildLanguageOrIgnoreChangesIncludingMovedDescriptors() {
        assertTrue(PublishedFileDelta.between(Map.of("pom.xml","old"),Map.of("pom.xml","new")).globalInputsChanged());
        assertTrue(PublishedFileDelta.between(Map.of("pom.xml","same"),Map.of("nested/pom.xml","same")).globalInputsChanged());
        assertTrue(PublishedFileDelta.between(Map.of(),Map.of("frontend/tsconfig.paths.json","a")).globalInputsChanged());
        assertTrue(PublishedFileDelta.between(Map.of(".gitignore","a"),Map.of()).globalInputsChanged());
        assertFalse(PublishedFileDelta.between(Map.of("README.md","a"),Map.of("README.md","b")).globalInputsChanged());
    }
}
