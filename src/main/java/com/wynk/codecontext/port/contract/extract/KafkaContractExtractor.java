package com.wynk.codecontext.port.contract.extract;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class KafkaContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.1.0";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
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
                SchemaEvidence evidence = schemaEvidence(repository, schema);
                add(direction.equals("outgoing"), topic, file.path(), "smallrye",
                        schema, evidence, exports, imports);
            }
            var listener = LISTENER.matcher(file.content());
            while (listener.find()) {
                String topic = ContractValueResolver.resolve(listener.group(1), constants, properties);
                if (topic != null) add(false, topic, file.path(), "spring-kafka",
                        null, SchemaEvidence.none(), exports, imports);
            }
            var send = SEND.matcher(file.content());
            if (file.path().endsWith(".java") || file.path().endsWith(".kt")) {
                while (send.find()) {
                    String topic = ContractValueResolver.resolve(send.group(1), constants, properties);
                    if (topic != null) add(true, topic, file.path(), "spring-kafka",
                            null, SchemaEvidence.none(), exports, imports);
                }
            }
            var producerRecord = PRODUCER_RECORD.matcher(file.content());
            if (file.path().endsWith(".java") || file.path().endsWith(".kt")) {
                while (producerRecord.find()) {
                    String topic = ContractValueResolver.resolve(producerRecord.group(1), constants, properties);
                    if (topic != null) add(true, topic, file.path(), "kafka-producer-record",
                            null, SchemaEvidence.none(), exports, imports);
                }
            }
            var pythonProduce = PYTHON_PRODUCE.matcher(file.content());
            while (pythonProduce.find()) {
                add(true, pythonProduce.group(1), file.path(), "python-confluent-kafka",
                        null, SchemaEvidence.none(), exports, imports);
            }
            var pythonSubscribe = PYTHON_SUBSCRIBE.matcher(file.content());
            while (pythonSubscribe.find()) {
                add(false, pythonSubscribe.group(1), file.path(), "python-confluent-kafka",
                        null, SchemaEvidence.none(), exports, imports);
            }
            var kafkaJsSend = KAFKAJS_SEND.matcher(file.content());
            while (kafkaJsSend.find()) {
                add(true, kafkaJsSend.group(1), file.path(), "kafkajs", null, SchemaEvidence.none(), exports, imports);
            }
            var kafkaJsSubscribe = KAFKAJS_SUBSCRIBE.matcher(file.content());
            while (kafkaJsSubscribe.find()) {
                add(false, kafkaJsSubscribe.group(1), file.path(), "kafkajs", null, SchemaEvidence.none(), exports, imports);
            }
            var goSaramaSend = GO_SARAMA_SEND.matcher(file.content());
            while (goSaramaSend.find()) {
                add(true, goSaramaSend.group(1), file.path(), "go-sarama", null, SchemaEvidence.none(), exports, imports);
            }
            var dotnetProduce = DOTNET_CONFLUENT_PRODUCE.matcher(file.content());
            while (dotnetProduce.find()) {
                add(true, dotnetProduce.group(1), file.path(), "dotnet-confluent-kafka", null, SchemaEvidence.none(), exports, imports);
            }
            var dotnetConsume = DOTNET_CONFLUENT_CONSUME.matcher(file.content());
            while (dotnetConsume.find()) {
                add(false, dotnetConsume.group(1), file.path(), "dotnet-confluent-kafka", null, SchemaEvidence.none(), exports, imports);
            }
            var rustProduce = RUST_RDKAFKA_PRODUCE.matcher(file.content());
            while (rustProduce.find()) {
                add(true, rustProduce.group(1), file.path(), "rust-rdkafka", null, SchemaEvidence.none(), exports, imports);
            }
            var rustSubscribe = RUST_RDKAFKA_SUBSCRIBE.matcher(file.content());
            while (rustSubscribe.find()) {
                add(false, rustSubscribe.group(1), file.path(), "rust-rdkafka", null, SchemaEvidence.none(), exports, imports);
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
            SchemaEvidence schema,
            Map<String, ContractExport> exports,
            Map<String, ContractImport> imports
    ) {
        ContractIdentity identity = ContractIdentity.ofKafka(topic.strip());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("framework", framework);
        metadata.put("direction", producer ? "producer" : "consumer");
        String comparisonSignature = topic + "|undeclared";
        if (schema != null && schema.hash() != null) {
            metadata.put("schema", schema.hash());
            metadata.put("schemaFile", schemaFile);
            if (schema.signature() != null && !schema.signature().isBlank()) {
                comparisonSignature = schema.signature();
                metadata.put("schemaFields", schema.fieldEvidence());
            } else {
                comparisonSignature = topic + "|" + schema.hash();
            }
        }
        metadata.put("signature", comparisonSignature);
        String key = identity.canonicalKey() + "|" + path;
        if (producer) {
            exports.putIfAbsent(key, new ContractExport(identity, ContractType.KAFKA, path, topic,
                    sha256(comparisonSignature), metadata));
        } else {
            imports.putIfAbsent(key, new ContractImport(identity, ContractType.KAFKA, path, topic,
                    null, metadata));
        }
    }

    private static String property(String content, String key) {
        var matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*([^#\\s]+)").matcher(content);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    private static SchemaEvidence schemaEvidence(RepositoryContext repository, String schemaFile) {
        if (schemaFile == null || schemaFile.isBlank()) {
            return SchemaEvidence.none();
        }
        try {
            java.nio.file.Path root = repository.root().toAbsolutePath().normalize();
            java.nio.file.Path candidate = root.resolve(schemaFile).normalize();
            if (!candidate.startsWith(root) || !java.nio.file.Files.isRegularFile(candidate)
                    || java.nio.file.Files.size(candidate) > MAX_BYTES) {
                return SchemaEvidence.none();
            }
            String content = java.nio.file.Files.readString(candidate, StandardCharsets.UTF_8);
            String hash = sha256(content.replaceAll("\\s+", ""));
            String lower = schemaFile.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".avsc") || lower.endsWith(".json")) {
                String fields = avroOrJsonFieldSignature(content);
                if (fields != null) {
                    return new SchemaEvidence(hash, fields, List.copyOf(parseFieldEvidence(fields)));
                }
            }
            return new SchemaEvidence(hash, null, List.of());
        } catch (Exception ignored) {
            return SchemaEvidence.none();
        }
    }

    /** Builds {@code fields{name:type:required|optional,...}} from Avro/JSON Schema object shapes. */
    static String avroOrJsonFieldSignature(String content) {
        try {
            JsonNode root = MAPPER.readTree(content);
            JsonNode fieldsNode = root.path("fields");
            if (!fieldsNode.isArray()) {
                // JSON Schema style
                JsonNode properties = root.path("properties");
                if (!properties.isObject()) return null;
                Set<String> required = new java.util.LinkedHashSet<>();
                if (root.path("required").isArray()) {
                    root.path("required").forEach(n -> { if (n.isString()) required.add(n.asText()); });
                }
                var ordered = new TreeMap<String, String>();
                properties.properties().forEach(entry -> {
                    String type = entry.getValue().path("type").asText("unknown");
                    ordered.put(entry.getKey(), entry.getKey() + ":" + type + ":"
                            + (required.contains(entry.getKey()) ? "required" : "optional"));
                });
                return "fields{" + String.join(",", ordered.values()) + "}";
            }
            var ordered = new TreeMap<String, String>();
            for (JsonNode field : fieldsNode) {
                String name = field.path("name").asText("");
                if (name.isBlank()) continue;
                boolean optional = isAvroOptional(field.path("type"));
                String type = avroTypeName(field.path("type"));
                ordered.put(name, name + ":" + type + ":" + (optional ? "optional" : "required"));
            }
            if (ordered.isEmpty()) return "fields{}";
            return "fields{" + String.join(",", ordered.values()) + "}";
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isAvroOptional(JsonNode type) {
        if (type != null && type.isArray()) {
            for (JsonNode part : type) {
                if (part.isString() && "null".equals(part.asText())) return true;
            }
        }
        return false;
    }

    private static String avroTypeName(JsonNode type) {
        if (type == null || type.isMissingNode() || type.isNull()) return "unknown";
        if (type.isString()) return type.asText();
        if (type.isArray()) {
            for (JsonNode part : type) {
                if (part.isString() && !"null".equals(part.asText())) return part.asText();
                if (part.isObject()) return avroTypeName(part);
            }
        }
        if (type.isObject()) {
            String t = type.path("type").asText("");
            if ("array".equals(t)) return "array<" + avroTypeName(type.path("items")) + ">";
            if ("record".equals(t)) return "record:" + type.path("name").asText("anonymous");
            if (!t.isBlank()) return t;
        }
        return "unknown";
    }

    private static List<String> parseFieldEvidence(String fieldsSignature) {
        if (fieldsSignature == null || !fieldsSignature.startsWith("fields{")) return List.of();
        String body = fieldsSignature.substring("fields{".length(), fieldsSignature.endsWith("}")
                ? fieldsSignature.length() - 1 : fieldsSignature.length());
        if (body.isBlank()) return List.of();
        return List.of(body.split(","));
    }

    private record SchemaEvidence(String hash, String signature, List<String> fieldEvidence) {
        static SchemaEvidence none() {
            return new SchemaEvidence(null, null, List.of());
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
