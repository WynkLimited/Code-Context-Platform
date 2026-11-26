package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmbeddingSpaceTest {
    @Test void digestConfigurationAndDimensionsArePartOfVectorIdentity() {
        var original=new EmbeddingSpace("model:tag","a".repeat(64),1024,8192);
        assertEquals(original,new EmbeddingSpace("model:tag","sha256:"+"A".repeat(64),1024,8192));
        assertEquals(64,original.fingerprint().length());
        assertNotEquals(original.fingerprint(),new EmbeddingSpace("model:tag","b".repeat(64),1024,8192).fingerprint());
        assertNotEquals(original.fingerprint(),new EmbeddingSpace("model:tag","a".repeat(64),768,8192).fingerprint());
        assertNotEquals(original.fingerprint(),new EmbeddingSpace("model:tag","a".repeat(64),1024,4096).fingerprint());
        assertEquals(false,original.metadata().get("truncate"));
    }
    @Test void refusesMissingProvenanceAndDetectsProviderChanges() {
        assertThrows(IllegalArgumentException.class,()->new EmbeddingSpace("model","not-a-digest",1024,8192));
        assertThrows(IllegalStateException.class,()->EmbeddingSpace.current(mock(EmbeddingModel.class),1024));
        var model=mock(EmbeddingModel.class,withSettings().extraInterfaces(EmbeddingSpace.IdentifiedModel.class));
        var before=new EmbeddingSpace("model:tag","a".repeat(64),1024,8192);
        when(((EmbeddingSpace.IdentifiedModel)model).embeddingSpace(1024)).thenReturn(before,new EmbeddingSpace("model:tag","b".repeat(64),1024,8192));
        before.requireCurrent(model);
        assertThrows(IllegalStateException.class,()->before.requireCurrent(model));
    }
}
