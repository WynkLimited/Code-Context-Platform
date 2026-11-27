package com.wynk.codecontext.ops;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PrometheusAlertsConfigTest {
    @Test void alertRulesParseAndNameRequiredSignals() throws Exception {
        Path alerts = Path.of("deploy/prometheus/alerts.yaml");
        assertTrue(Files.isRegularFile(alerts), alerts.toAbsolutePath().toString());
        Object loaded = new Yaml().load(Files.readString(alerts));
        assertInstanceOf(Map.class, loaded);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) loaded;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) root.get("groups");
        assertFalse(groups.isEmpty());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) groups.get(0).get("rules");
        var names = rules.stream().map(r -> String.valueOf(r.get("alert"))).toList();
        assertTrue(names.contains("CodeContextIndexJobFailures"), names.toString());
        assertTrue(names.contains("CodeContextCleanupOutboxFailed"), names.toString());
        assertTrue(names.contains("CodeContextMcpErrors"), names.toString());
        assertTrue(names.contains("CodeContextInstanceDown"), names.toString());
        for (var rule : rules) {
            assertNotNull(rule.get("expr"));
            assertNotNull(rule.get("labels"));
        }
    }
}
