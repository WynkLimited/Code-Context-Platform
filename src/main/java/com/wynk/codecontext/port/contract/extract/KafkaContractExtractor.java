package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class KafkaContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Pattern SMALLRYE_TOPIC = Pattern.compile(
            "(?m)^\\s*mp\\.messaging\\.(outgoing|incoming)\\.([^.\\s=]+)\\.topic\\s*=\\s*([^#\\s]+)");
    private static final Pattern LISTENER = Pattern.compile(
            "@KafkaListener\\s*\\([^)]*topics\\s*=\\s*(?:\\[)?\\s*([^,\\])]+)");
    private static final Pattern SEND = Pattern.compile(
            "\\b(?:kafkaTemplate|producer|emitter)\\s*\\.\\s*(?:send|sendAndForget)\\s*\\(\\s*([^,\\)]+)");
    private static final Pattern PRODUCER_RECORD = Pattern.compile(
            "\\bnew\\s+ProducerRecord(?:<[^>]+>)?\\s*\\(\\s*([^,\\)]+)");
    private static final Pattern PYTHON_PRODUCE = Pattern.compile(
            "\\b(?:producer|kafka_producer)\\.produce\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern PYTHON_SUBSCRIBE = Pattern.compile(
            "\\b(?:consumer|kafka_consumer)\\.subscribe\\s*\\(\\s*\\[\\s*\"([^\"]+)\"");
    private static final Pattern KAFKAJS_SEND = Pattern.compile(
            "\\b(?:producer|kafkaProducer)\\.send\\s*\\(\\s*\\{[^}]*\\btopic\\s*:\\s*[`'\"]([^`'\"]+)[`'\"]");
    private static final Pattern KAFKAJS_SUBSCRIBE = Pattern.compile(
            "\\b(?:consumer|kafkaConsumer)\\.subscribe\\s*\\(\\s*\\{[^}]*\\btopic\\s*:\\s*[`'\"]([^`'\"]+)[`'\"]");
    private static final Pattern GO_SARAMA_SEND = Pattern.compile(
            "\\b(?:producer|syncProducer|asyncProducer)\\.(?:SendMessage|Input)\\s*\\(\\s*(?:&?sarama\\.ProducerMessage\\s*\\{)?[^}]*\\bTopic\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DOTNET_CONFLUENT_PRODUCE = Pattern.compile(
            "\\b(?:producer|kafkaProducer)\\.Produce(?:Async)?\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern DOTNET_CONFLUENT_CONSUME = Pattern.compile(
            "\\b(?:consumer|kafkaConsumer)\\.Subscribe\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern RUST_RDKAFKA_PRODUCE = Pattern.compile(
            "\\b(?:producer|future_producer)\\.send\\s*\\(\\s*FutureRecord::to\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern RUST_RDKAFKA_SUBSCRIBE = Pattern.compile(
            "\\b(?:consumer|stream_consumer)\\.subscribe\\s*\\(\\s*&?\\[\\s*\"([^\"]+)\"");

    @Override public String id() { return ContractType.KAFKA.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.KAFKA; }
    @Override public boolean available() { return true; }

    @Override
    public boolean mayDefineContract(String path) {
        return path != null && (path.endsWith(".properties") || path.endsWith(".yaml")
                || path.endsWith(".yml") || path.endsWith(".java") || path.endsWith(".kt")
                || path.endsWith(".py") || path.endsWith(".js") || path.endsWith(".ts")
                || path.endsWith(".go") || path.endsWith(".cs") || path.endsWith(".rs"));
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        return scan(repository).exports();
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        return scan(repository).imports();
    }

    private Scan scan(RepositoryContext repository) {
        var exports = new LinkedHashMap<String, ContractExport>();
        var imports = new LinkedHashMap<String, ContractImport>();
        List<ExtractorFileSupport.SourceFile> files = ExtractorFileSupport.read(
                repository, Set.of(".properties", ".yaml", ".yml", ".java", ".kt", ".py", ".js", ".ts", ".go", ".cs", ".rs"), MAX_BYTES);
        Map<String, String> properties = ContractValueResolver.properties(files);
        for (var file : files) {
            Map<String, String> constants = ContractValueResolver.constants(file.content());
            var topicMatcher = SMALLRYE_TOPIC.matcher(file.content());
            while (topicMatcher.find()) {
                String direction = topicMatcher.group(1);
                String channel = topicMatcher.group(2);
                String topic = ContractValueResolver.resolve(topicMatcher.group(3), constants, properties);
                if (topic == null) continue;
                String schema = property(file.content(),
                        "mp.messaging." + direction + "." + channel + ".schema");
                if (schema == null) {
                    schema = property(file.content(),
                            "mp.messaging." + direction + "." + channel + ".value.schema");
                }
                add(direction.equals("outgoing"), topic, file.path(), "smallrye",
                        schema, schemaEvidence(repository, schema), exports, imports);
            }
            var listener = LISTENER.matcher(file.content());
            while (listener.find()) {
                String topic = ContractValueResolver.resolve(listener.group(1), constants, properties);
                if (topic != null) add(false, topic, file.path(), "spring-kafka",
                        null, null, exports, imports);
            }
            var send = SEND.matcher(file.content());
            if (file.path().endsWith(".java") || file.path().endsWith(".kt")) {
                while (send.find()) {
                    String topic = ContractValueResolver.resolve(send.group(1), constants, properties);
                    if (topic != null) add(true, topic, file.path(), "spring-kafka",
                            null, null, exports, imports);
                }
            }
            var producerRecord = PRODUCER_RECORD.matcher(file.content());
            if (file.path().endsWith(".java") || file.path().endsWith(".kt")) {
                while (producerRecord.find()) {
                    String topic = ContractValueResolver.resolve(producerRecord.group(1), constants, properties);
                    if (topic != null) add(true, topic, file.path(), "kafka-producer-record",
                            null, null, exports, imports);
                }
            }
            var pythonProduce = PYTHON_PRODUCE.matcher(file.content());
            while (pythonProduce.find()) {
                add(true, pythonProduce.group(1), file.path(), "python-confluent-kafka",
                        null, null, exports, imports);
            }
            var pythonSubscribe = PYTHON_SUBSCRIBE.matcher(file.content());
            while (pythonSubscribe.find()) {
                add(false, pythonSubscribe.group(1), file.path(), "python-confluent-kafka",
                        null, null, exports, imports);
            }
            var kafkaJsSend = KAFKAJS_SEND.matcher(file.content());
            while (kafkaJsSend.find()) {
                add(true, kafkaJsSend.group(1), file.path(), "kafkajs", null, null, exports, imports);
            }
            var kafkaJsSubscribe = KAFKAJS_SUBSCRIBE.matcher(file.content());
            while (kafkaJsSubscribe.find()) {
                add(false, kafkaJsSubscribe.group(1), file.path(), "kafkajs", null, null, exports, imports);
            }
            var goSaramaSend = GO_SARAMA_SEND.matcher(file.content());
            while (goSaramaSend.find()) {
                add(true, goSaramaSend.group(1), file.path(), "go-sarama", null, null, exports, imports);
            }
            var dotnetProduce = DOTNET_CONFLUENT_PRODUCE.matcher(file.content());
            while (dotnetProduce.find()) {
                add(true, dotnetProduce.group(1), file.path(), "dotnet-confluent-kafka", null, null, exports, imports);
            }
            var dotnetConsume = DOTNET_CONFLUENT_CONSUME.matcher(file.content());
            while (dotnetConsume.find()) {
                add(false, dotnetConsume.group(1), file.path(), "dotnet-confluent-kafka", null, null, exports, imports);
            }
            var rustProduce = RUST_RDKAFKA_PRODUCE.matcher(file.content());
            while (rustProduce.find()) {
                add(true, rustProduce.group(1), file.path(), "rust-rdkafka", null, null, exports, imports);
            }
            var rustSubscribe = RUST_RDKAFKA_SUBSCRIBE.matcher(file.content());
            while (rustSubscribe.find()) {
                add(false, rustSubscribe.group(1), file.path(), "rust-rdkafka", null, null, exports, imports);
            }
        }
        return new Scan(List.copyOf(exports.values()), List.copyOf(imports.values()));
    }

    private static void add(
            boolean producer,
            String topic,
            String path,
            String framework,
            String schemaFile,
            String schema,
            Map<String, ContractExport> exports,
            Map<String, ContractImport> imports
    ) {
        ContractIdentity identity = ContractIdentity.ofKafka(topic.strip());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("framework", framework);
        metadata.put("direction", producer ? "producer" : "consumer");
        if (schema != null) {
            metadata.put("schema", schema);
            metadata.put("schemaFile", schemaFile);
        }
        String key = identity.canonicalKey() + "|" + path;
        if (producer) {
            String signature = topic + "|" + (schema == null ? "undeclared" : schema);
            exports.putIfAbsent(key, new ContractExport(identity, ContractType.KAFKA, path, topic,
                    sha256(signature), metadata));
        } else {
            imports.putIfAbsent(key, new ContractImport(identity, ContractType.KAFKA, path, topic,
                    null, metadata));
        }
    }

    private static String property(String content, String key) {
        var matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*([^#\\s]+)").matcher(content);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static String schemaEvidence(RepositoryContext repository, String schemaFile) {
        if (schemaFile == null || schemaFile.isBlank()) {
            return null;
        }
        try {
            java.nio.file.Path root = repository.root().toAbsolutePath().normalize();
            java.nio.file.Path candidate = root.resolve(schemaFile).normalize();
            if (!candidate.startsWith(root) || !java.nio.file.Files.isRegularFile(candidate)
                    || java.nio.file.Files.size(candidate) > MAX_BYTES) {
                return null;
            }
            String content = java.nio.file.Files.readString(candidate, StandardCharsets.UTF_8);
            return sha256(content.replaceAll("\\s+", ""));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Scan(List<ContractExport> exports, List<ContractImport> imports) {}
}
