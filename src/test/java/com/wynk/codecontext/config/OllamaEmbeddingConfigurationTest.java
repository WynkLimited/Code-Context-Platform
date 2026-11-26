package com.wynk.codecontext.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class OllamaEmbeddingConfigurationTest {
    @Test void sendsTheConfiguredContextAndRefusesSilentInputTruncation() throws Exception {
        var received=new AtomicReference<JsonNode>();
        var digest=new AtomicReference<>("a".repeat(64));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        try {
            server.createContext("/api/tags",exchange->{
                byte[] response=("{\"models\":[{\"name\":\"wire-model:latest\",\"model\":\"wire-model:latest\",\"digest\":\""+digest.get()+"\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
            });
            server.createContext("/api/embed",exchange->{
                received.set(JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes()));
                byte[] response="{\"model\":\"wire-model\",\"embeddings\":[[0.1,0.2,0.3]],\"prompt_eval_count\":1}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
            });
            server.start();
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OllamaEmbeddingAutoConfiguration.class))
                    .withUserConfiguration(OllamaEmbeddingConfiguration.class)
                    .withBean(OllamaApi.class,()->OllamaApi.builder().baseUrl("http://127.0.0.1:"+server.getAddress().getPort()).build())
                    .withPropertyValues("spring.ai.ollama.embedding.model=wire-model",
                            "spring.ai.ollama.embedding.num-ctx=8192","spring.ai.ollama.embedding.truncate=false",
                            "spring.ai.ollama.embedding.keep-alive=5m")
                    .run(context->{
                        assertNull(context.getStartupFailure());
                        assertEquals(1,context.getBeanNamesForType(EmbeddingModel.class).length);
                        assertEquals(3,context.getBean(EmbeddingModel.class).embed("fixture code").length);
                        assertNotNull(received.get());
                        assertEquals("wire-model",received.get().path("model").asText());
                        assertEquals(8192,received.get().path("options").path("num_ctx").asInt());
                        assertFalse(received.get().path("truncate").asBoolean(true));
                        assertEquals("5m",received.get().path("keep_alive").asText());
                        var model=context.getBean(EmbeddingModel.class);
                        var space=com.wynk.codecontext.index.EmbeddingSpace.current(model,3);
                        assertEquals("a".repeat(64),space.digest());assertEquals(8192,space.contextTokens());
                        space.requireCurrent(model);
                        digest.set("b".repeat(64));
                        assertThrows(IllegalStateException.class,()->space.requireCurrent(model));
                        digest.set("invalid");
                        assertThrows(IllegalArgumentException.class,()->com.wynk.codecontext.index.EmbeddingSpace.current(model,3));
                    });
        } finally {server.stop(0);}
    }
    @Test void rejectsInvalidContextAndSilentTruncationSettings() {
        for(String invalid:new String[]{"spring.ai.ollama.embedding.num-ctx=0","spring.ai.ollama.embedding.truncate=true"}) {
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(OllamaEmbeddingAutoConfiguration.class))
                    .withUserConfiguration(OllamaEmbeddingConfiguration.class)
                    .withBean(OllamaApi.class,()->OllamaApi.builder().baseUrl("http://127.0.0.1:1").build())
                    .withPropertyValues("spring.ai.ollama.embedding.model=wire-model",invalid)
                    .run(context->{
                        Throwable failure=context.getStartupFailure();assertNotNull(failure);
                        while(failure.getCause()!=null)failure=failure.getCause();
                        assertInstanceOf(IllegalArgumentException.class,failure);
                        assertTrue(failure.getMessage().contains(invalid.contains("num-ctx")?"embedding context":"silent embedding truncation"));
                    });
        }
    }

}
