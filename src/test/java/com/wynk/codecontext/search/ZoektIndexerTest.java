package com.wynk.codecontext.search;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZoektIndexerTest {
    @TempDir Path root;
    @Test void indexesConfiguredBranchAndPreservesGenerationIdentity() throws Exception {
        Path executable=root.resolve("capture-indexer");
        Files.writeString(executable,"#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        assertTrue(executable.toFile().setExecutable(true));
        Path index=root.resolve("index");
        var config=new CodeContextProperties(root.toString(),root.toString(),index.toString(),"","","","","github.com","http://localhost",1024,1048576,6000,2,300,
                new CodeContextProperties.Zoekt(executable.toString(),"zoekt-local-sync","http://localhost:6070"),new CodeContextProperties.Graphify("graphify",false));
        UUID repository=UUID.randomUUID(),generation=UUID.randomUUID();
        var record=new RepositoryRecord(repository,"Fixture","repo","file:///fixture","team","release/v1","LIBRARY",root.toString());
        new ZoektIndexer(config,JsonMapper.builder().build()).index(record,root,generation);
        var args=Files.readAllLines(index.resolve("index-"+repository+".log"));
        assertEquals("release/v1",args.get(args.indexOf("-branches")+1));
        assertFalse(args.contains("HEAD"));
        var metadata=JsonMapper.builder().build().readTree(index.resolve("meta-"+repository+".json").toFile());
        assertEquals("Fixture/repo@"+generation,metadata.path("name").asText());
    }
}
