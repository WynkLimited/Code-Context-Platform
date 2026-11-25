package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KafkaContractExtractorTest {

    @TempDir
    Path root;

    @Test
    void extractsSmallRyeAndSpringProducerConsumerTopics() throws Exception {
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.createDirectories(root.resolve("schemas"));
        Files.writeString(root.resolve("schemas/campaign.avsc"),
                "{\"type\":\"record\",\"name\":\"Campaign\",\"fields\":[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"tag\",\"type\":[\"null\",\"string\"],\"default\":null}]}");
        Files.writeString(root.resolve("src/main/resources/application.properties"), """
                mp.messaging.outgoing.campaigns.topic=campaign-events
                mp.messaging.outgoing.campaigns.schema=schemas/campaign.avsc
                mp.messaging.incoming.audit.topic=campaign-audit
                """);
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/Listeners.java"), """
                @KafkaListener(topics = "spring-input") void consume(String value) {}
                void publish() { kafkaTemplate.send("spring-output", "value"); }
                """);
        var extractor = new KafkaContractExtractor();
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(Set.of("kafka:campaign-events", "kafka:spring-output"),
                extractor.extractExports(repo).stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("kafka:campaign-audit", "kafka:spring-input"),
                extractor.extractImports(repo).stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        var campaign = extractor.extractExports(repo).stream()
                .filter(value -> value.canonicalKey().equals("kafka:campaign-events"))
                .findFirst().orElseThrow();
        assertEquals("schemas/campaign.avsc", campaign.metadata().get("schemaFile"));
        assertEquals("fields{id:string:required,tag:string:optional}", campaign.metadata().get("signature"));
        assertEquals("producer", campaign.metadata().get("direction"));
    }

    @Test
    void resolvesConfigDerivedTopicFromNestedYamlPlaceholder() throws Exception {
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application.yaml"), """
                messaging:
                  orders:
                    topic: order-events
                """);
        Files.writeString(root.resolve("src/main/resources/application.properties"), """
                mp.messaging.outgoing.orders.topic=${messaging.orders.topic}
                """);
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);
        assertEquals(Set.of("kafka:order-events"), new KafkaContractExtractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void extractsPythonConfluentKafkaTopics() throws Exception {
        Files.writeString(root.resolve("consumer.py"), "consumer.subscribe([\"campaign-events\"])");
        Files.writeString(root.resolve("producer.py"), "producer.produce(\"campaign-audit\", value=b\"x\")");
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(Set.of("kafka:campaign-audit"), new KafkaContractExtractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("kafka:campaign-events"), new KafkaContractExtractor().extractImports(repo)
                .stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void resolvesJavaKotlinConstantsAndLocalPropertyPlaceholders() throws Exception {
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application.properties"), "campaign.topic=campaign-events\n");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/CampaignProducer.java"), """
                class CampaignProducer {
                  static final String TOPIC = "${campaign.topic}";
                  void publish() { kafkaTemplate.send(TOPIC, "value"); }
                }
                """);
        Files.createDirectories(root.resolve("src/main/kotlin"));
        Files.writeString(root.resolve("src/main/kotlin/CampaignListener.kt"), """
                const val TOPIC = "${campaign.topic}"
                @KafkaListener(topics = [TOPIC]) fun consume(value: String) = Unit
                """);
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(Set.of("kafka:campaign-events"), new KafkaContractExtractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("kafka:campaign-events"), new KafkaContractExtractor().extractImports(repo)
                .stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void resolvesOneAliasAndNestedYamlButRejectsDynamicOrUnresolvedTopics() throws Exception {
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application.yaml"), """
                campaign:
                  kafka:
                    topic: campaign-events
                """);
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/CampaignProducer.java"), """
                class CampaignProducer {
                  private static final String TOPIC = "${campaign.kafka.topic}";
                  private static final String CAMPAIGN_TOPIC = TOPIC;
                  void publish() { kafkaTemplate.send(CAMPAIGN_TOPIC, "value"); }
                  void dynamic(String suffix) { kafkaTemplate.send("campaign-" + suffix, "value"); }
                  void unknown() { kafkaTemplate.send(UNKNOWN_TOPIC, "value"); }
                }
                """);
        Files.createDirectories(root.resolve("src/main/kotlin"));
        Files.writeString(root.resolve("src/main/kotlin/CampaignListener.kt"), """
                class CampaignListener {
                  companion object { const val TOPIC = "${campaign.kafka.topic}" }
                  @KafkaListener(topics = [TOPIC]) fun consume(value: String) = Unit
                }
                """);
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);
        var files = ExtractorFileSupport.read(repo, Set.of(".java", ".kt", ".yaml"), 1024 * 1024);
        var producer = files.stream().filter(file -> file.path().endsWith("CampaignProducer.java")).findFirst().orElseThrow();
        assertEquals("campaign-events", ContractValueResolver.resolve("CAMPAIGN_TOPIC",
                ContractValueResolver.constants(producer.content()), ContractValueResolver.properties(files)));

        assertEquals(Set.of("kafka:campaign-events"), new KafkaContractExtractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("kafka:campaign-events"), new KafkaContractExtractor().extractImports(repo)
                .stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void extractsKafkaJsTopics() throws Exception {
        Files.writeString(root.resolve("client.ts"), "producer.send({ topic: 'campaign-audit', messages: [] });\nconsumer.subscribe({ topic: 'campaign-events' });");
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(Set.of("kafka:campaign-audit"), new KafkaContractExtractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("kafka:campaign-events"), new KafkaContractExtractor().extractImports(repo)
                .stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void extractsGoSaramaAndDotNetConfluentKafkaTopics() throws Exception {
        Files.writeString(root.resolve("producer.go"), "producer.SendMessage(&sarama.ProducerMessage{Topic: \"campaign-audit\"})");
        Files.writeString(root.resolve("client.cs"), "await producer.ProduceAsync(\"campaign-output\", message); consumer.Subscribe(\"campaign-input\");");
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(Set.of("kafka:campaign-audit", "kafka:campaign-output"), new KafkaContractExtractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("kafka:campaign-input"), new KafkaContractExtractor().extractImports(repo)
                .stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void extractsRustRdkafkaTopics() throws Exception {
        Files.writeString(root.resolve("client.rs"), "producer.send(FutureRecord::to(\"campaign-audit\"), timeout); consumer.subscribe(&[\"campaign-events\"]);");
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(Set.of("kafka:campaign-audit"), new KafkaContractExtractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("kafka:campaign-events"), new KafkaContractExtractor().extractImports(repo)
                .stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }
}
