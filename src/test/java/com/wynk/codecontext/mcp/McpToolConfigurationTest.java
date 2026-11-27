package com.wynk.codecontext.mcp;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpToolConfigurationTest {

    @Test
    void callbackProviderDiscoversExactlyTheSixApplicationTools() {
        var tools = new CodeContextTools(null, null, null, null, null, null, null, null, null, null, null);
        var callbacks = new McpToolConfiguration().codeContextToolsCallbackProvider(tools).getToolCallbacks();
        Set<String> names = Arrays.stream(callbacks)
                .map(callback -> callback.getToolDefinition().name())
                .collect(Collectors.toSet());

        assertEquals(6, callbacks.length);
        assertEquals(Set.of("search_code", "read_file", "find_symbol", "get_neighbors", "semantic_search", "impact_analysis"), names);
    }
}
