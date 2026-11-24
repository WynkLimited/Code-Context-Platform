package com.wynk.codecontext.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class McpToolConfiguration {

    @Bean
    ToolCallbackProvider codeContextToolsCallbackProvider(CodeContextTools codeContextTools) {
        return MethodToolCallbackProvider.builder().toolObjects(codeContextTools).build();
    }
}
