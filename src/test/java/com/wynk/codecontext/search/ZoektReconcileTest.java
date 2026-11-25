package com.wynk.codecontext.search;

import com.wynk.codecontext.config.CodeContextProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ZoektReconcileTest {
    @TempDir Path root;

    @Test void listsIndexedGenerationsFromMetadataAndRemovesRepositoryFiles() throws Exception {
        Path index=Files.createDirectories(root.resolve("index"));
        UUID repository=UUID.randomUUID(),generation=UUID.randomUUID();
        Files.writeString(index.resolve("meta-"+repository+".json"),
                "{\"name\":\"Acme/payments@"+generation+"\",\"description\":\"fixture\"}");
        Files.writeString(index.resolve("index-"+repository+".log"),"log");
        Files.writeString(index.resolve("meta-broken.json"),"{");
        var config=new CodeContextProperties(root.toString(),root.toString(),index.toString(),"","","","","github.com","http://localhost",
                1024,1048576,6000,2,300,new CodeContextProperties.Zoekt("zoekt-git-index","zoekt-local-sync","http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify",false));
        var indexer=new ZoektIndexer(config,JsonMapper.builder().build());
        var listed=indexer.listIndexedGenerations();
        assertEquals(1,listed.size());
        assertEquals("Acme",listed.get(0).owner());
        assertEquals("payments",listed.get(0).name());
        assertEquals(generation,listed.get(0).generation());
        indexer.removeRepositoryMetadata(repository);
        assertFalse(Files.exists(index.resolve("meta-"+repository+".json")));
        assertFalse(Files.exists(index.resolve("index-"+repository+".log")));
    }
}
