package com.wynk.codecontext.port.contract.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractEntryMetadata;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;
import com.wynk.codecontext.port.gnostic.GnosticModels.SourceRange;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class RestContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";
    private static final long MAX_BYTES = 2L * 1024 * 1024;
    private static final Pattern CLASS_PATH = Pattern.compile(
            "@(?:Path|RequestMapping)\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?([^,\\)]+)");
    private static final Pattern JAX_METHOD = Pattern.compile(
            "@(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\b");
    private static final Pattern METHOD_PATH = Pattern.compile("@Path\\s*\\(\\s*([^,\\)]+)");
    private static final Pattern SPRING_METHOD = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete)Mapping\\s*\\(\\s*(?:(?:value|path)\\s*=\\s*)?([^,\\)]+)");
    // Language-specific client APIs. These establish REST consumer edges; they are deliberately
    // separate from the Java annotation path and never infer an endpoint from arbitrary strings.
    private static final Pattern PYTHON_REQUEST = Pattern.compile(
            "\\b(?:requests|httpx)\\.(get|post|put|patch|delete)\\s*\\(\\s*[furbFURB]*\"([^\"]+)\"");
    private static final Pattern GO_REQUEST = Pattern.compile(
            "\\bhttp\\.(Get|Post|Put|Patch|Delete)\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern JS_FETCH = Pattern.compile(
            "\\bfetch\\s*\\(\\s*[`\"]([^`\"]+)[`\"](?:\\s*,\\s*\\{[^}]*method\\s*:\\s*[`\"](GET|POST|PUT|PATCH|DELETE)[`\"])?");
    private static final Pattern DOTNET_HTTP = Pattern.compile(
            "\\b(?:client|httpClient)\\.(Get|Post|Put|Patch|Delete)Async\\s*\\(\\s*\"([^\"]+)\"");
    private static final Pattern WEB_CLIENT = Pattern.compile(
            "\\b(?:webClient|client)\\.(get|post|put|patch|delete)\\s*\\(\\s*\\)\\s*\\.uri\\s*\\(\\s*([^,\\)]+)");
    private static final Pattern REST_TEMPLATE = Pattern.compile(
            "\\b(?:restTemplate|template)\\.(getForObject|getForEntity|postForObject|postForEntity|put|delete)\\s*\\(\\s*([^,\\)]+)");
    private static final Pattern RUST_REQWEST = Pattern.compile(
            "\\b(?:client|http_client)\\.(get|post|put|patch|delete)\\s*\\(\\s*\"([^\"]+)\"");

    @Override public String id() { return ContractType.REST.id(); }
    @Override public String version() { return VERSION; }
    @Override public ContractType type() { return ContractType.REST; }
    @Override public boolean available() { return true; }

    @Override
    public boolean mayDefineContract(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".kt") || lower.endsWith(".go")
                || lower.endsWith(".py") || lower.endsWith(".ts") || lower.endsWith(".js")
                || lower.endsWith(".cs") || lower.endsWith(".rs")
                || lower.endsWith("openapi.yaml") || lower.endsWith("openapi.yml")
                || lower.endsWith("openapi.json");
    }

    @Override public List<ContractExport> extractExports(RepositoryContext repository) {
        return scan(repository).exports();
    }

    @Override public List<ContractImport> extractImports(RepositoryContext repository) {
        return scan(repository).imports();
    }

    private Scan scan(RepositoryContext repository) {
        var exports = new LinkedHashMap<String, ContractExport>();
        var imports = new LinkedHashMap<String, ContractImport>();
        List<ExtractorFileSupport.SourceFile> files = ExtractorFileSupport.read(
                repository, Set.of(".java", ".kt", ".go", ".py", ".ts", ".js", ".cs", ".rs", ".properties", ".yaml", ".yml", ".json"), MAX_BYTES);
        Map<String, String> properties = ContractValueResolver.properties(files);
        for (var file : files) {
            Map<String, String> constants = ContractValueResolver.constants(file.content());
            boolean client = file.content().contains("@RegisterRestClient")
                    || file.content().contains("@FeignClient");
            String classPath = firstGroup(CLASS_PATH, beforeClassDeclaration(file.content()));
            String basePath = classPath.isBlank() ? ""
                    : ContractValueResolver.resolve(classPath, constants, properties);
            if (basePath == null) {
                // An unresolved class-level base path cannot be safely joined to method paths.
                continue;
            }
            var jax = JAX_METHOD.matcher(file.content());
            while (jax.find()) {
                int end = Math.min(file.content().length(), jax.end() + 500);
                String methodBlock = file.content().substring(jax.end(), end);
                String methodPath = firstGroup(METHOD_PATH, methodBlock);
                if (methodPath.isEmpty()) {
                    String precedingAnnotations = precedingAnnotationBlock(file.content(), jax.start());
                    methodPath = lastGroup(METHOD_PATH, precedingAnnotations);
                    if (methodPath.equals(basePath)) {
                        methodPath = "";
                    }
                }
                String resolvedMethodPath = ContractValueResolver.resolve(methodPath, constants, properties);
                if (resolvedMethodPath == null) continue;
                add(repository, client, jax.group(1), join(basePath, resolvedMethodPath), file.path(),
                        file.content(), jax.start(), "jax-rs", jax.group() + methodBlock, exports, imports);
            }
            var spring = SPRING_METHOD.matcher(file.content());
            while (spring.find()) {
                String resolvedMethodPath = ContractValueResolver.resolve(spring.group(2), constants, properties);
                if (resolvedMethodPath == null) continue;
                add(repository, client, spring.group(1), join(basePath, resolvedMethodPath), file.path(),
                        file.content(), spring.start(), "spring-web", spring.group(), exports, imports);
            }
            var python = PYTHON_REQUEST.matcher(file.content());
            while (python.find()) {
                add(repository, true, python.group(1), pathFromUrl(python.group(2)), file.path(),
                        file.content(), python.start(), "python-requests", python.group(), exports, imports);
            }
            var go = GO_REQUEST.matcher(file.content());
            while (go.find()) {
                add(repository, true, go.group(1), pathFromUrl(go.group(2)), file.path(),
                        file.content(), go.start(), "go-net-http", go.group(), exports, imports);
            }
            var fetch = JS_FETCH.matcher(file.content());
            while (fetch.find()) {
                add(repository, true, fetch.group(2) == null ? "GET" : fetch.group(2), pathFromUrl(fetch.group(1)), file.path(),
                        file.content(), fetch.start(), "javascript-fetch", fetch.group(), exports, imports);
            }
            var dotnet = DOTNET_HTTP.matcher(file.content());
            while (dotnet.find()) {
                add(repository, true, dotnet.group(1), pathFromUrl(dotnet.group(2)), file.path(),
                        file.content(), dotnet.start(), "dotnet-httpclient", dotnet.group(), exports, imports);
            }
            var webClient = WEB_CLIENT.matcher(file.content());
            while (webClient.find()) {
                String path = ContractValueResolver.resolve(webClient.group(2), constants, properties);
                if (path == null) continue;
                add(repository, true, webClient.group(1), pathFromUrl(path), file.path(),
                        file.content(), webClient.start(), "spring-webclient", webClient.group(), exports, imports);
            }
            var restTemplate = REST_TEMPLATE.matcher(file.content());
            while (restTemplate.find()) {
                String path = ContractValueResolver.resolve(restTemplate.group(2), constants, properties);
                if (path == null) continue;
                add(repository, true, restMethod(restTemplate.group(1)), pathFromUrl(path), file.path(),
                        file.content(), restTemplate.start(), "spring-rest-template", restTemplate.group(), exports, imports);
            }
            var rust = RUST_REQWEST.matcher(file.content());
            while (rust.find()) {
                add(repository, true, rust.group(1), pathFromUrl(rust.group(2)), file.path(),
                        file.content(), rust.start(), "rust-reqwest", rust.group(), exports, imports);
            }
            if (file.path().toLowerCase(Locale.ROOT).matches(".*openapi\\.(yaml|yml)$")) {
                parseOpenApiYaml(repository, file.path(), file.content(), exports);
            } else if (file.path().toLowerCase(Locale.ROOT).endsWith("openapi.json")) {
                parseOpenApiJson(repository, file.path(), file.content(), exports);
            }
        }
        return new Scan(List.copyOf(exports.values()), List.copyOf(imports.values()));
    }

    private static void parseOpenApiYaml(
            RepositoryContext repository,
            String sourceFile,
            String content,
            Map<String, ContractExport> exports
    ) {
        String[] lines = content.split("\\R");
        Pattern pathLine = Pattern.compile("^(\\s+)[\"']?(/[^\"']*)[\"']?:\\s*$");
        Pattern methodLine = Pattern.compile("^(\\s+)(get|post|put|patch|delete|head|options):\\s*$",
                Pattern.CASE_INSENSITIVE);
        String path = null;
        int pathIndent = -1;
        int pathsIndent = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].matches("^\\s*paths:\\s*$")) {
                pathsIndent = leadingSpaces(lines[i]);
                path = null;
                continue;
            }
            if (pathsIndent < 0) {
                continue;
            }
            if (!lines[i].isBlank() && leadingSpaces(lines[i]) <= pathsIndent) {
                break;
            }
            var pathMatcher = pathLine.matcher(lines[i]);
            if (pathMatcher.find() && pathMatcher.group(1).length() > pathsIndent) {
                path = pathMatcher.group(2);
                pathIndent = pathMatcher.group(1).length();
                continue;
            }
            var method = methodLine.matcher(lines[i]);
            if (path == null || !method.find() || method.group(1).length() <= pathIndent) {
                continue;
            }
            int methodIndent = method.group(1).length();
            var signature = new StringBuilder(method.group(2).toLowerCase(Locale.ROOT)).append(':');
            for (int j = i + 1; j < lines.length; j++) {
                String next = lines[j];
                if (!next.isBlank() && leadingSpaces(next) <= methodIndent) break;
                if (!next.isBlank()) signature.append('\n').append(next.strip());
            }
            add(repository, false, method.group(2), path, sourceFile, null, -1, "openapi",
                    signature.toString(), exports, new LinkedHashMap<>());
        }
    }

    private static void parseOpenApiJson(
            RepositoryContext repository,
            String sourceFile,
            String content,
            Map<String, ContractExport> exports
    ) {
        try {
            JsonNode paths = new ObjectMapper().readTree(content).path("paths");
            paths.fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
                if (operation.getKey().matches("(?i)get|post|put|patch|delete|head|options")) {
                    add(repository, false, operation.getKey(), path.getKey(), sourceFile, null, -1, "openapi",
                            operation.getValue().toString(), exports, new LinkedHashMap<>());
                }
            }));
        } catch (Exception ignored) {
            // Invalid OpenAPI reduces coverage; it never fails the origin review.
        }
    }

    private static void add(
            RepositoryContext repository,
            boolean client,
            String method,
            String path,
            String sourceFile,
            String fileContent,
            int anchorOffset,
            String framework,
            String signature,
            Map<String, ContractExport> exports,
            Map<String, ContractImport> imports
    ) {
        ContractIdentity identity = ContractIdentity.ofRest(method, path);
        String normalizedSignature = signature.replaceAll("\\s+", " ").strip();
        String resolved = method.strip().toUpperCase(Locale.ROOT) + " " + path;
        String fingerprint = sha256(normalizedSignature);
        SourceRange range = anchorOffset >= 0 && fileContent != null
                ? ContractEntryMetadata.lineRange(fileContent, anchorOffset, anchorOffset + 1)
                : null;
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("framework", framework);
        base.put("signature", normalizedSignature);
        Map<String, Object> metadata = ContractEntryMetadata.forRestEntry(
                base,
                repository.commit(),
                sourceFile,
                identity.canonicalKey(),
                range,
                resolved,
                fingerprint,
                client,
                VERSION);
        String key = identity.canonicalKey() + "|" + sourceFile;
        if (client) {
            imports.putIfAbsent(key, new ContractImport(identity, ContractType.REST, sourceFile,
                    identity.canonicalKey(), null, metadata));
        } else {
            exports.putIfAbsent(key, new ContractExport(identity, ContractType.REST, sourceFile,
                    identity.canonicalKey(), fingerprint, metadata));
        }
    }

    private static String firstGroup(Pattern pattern, String content) {
        var matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String lastGroup(Pattern pattern, String content) {
        var matcher = pattern.matcher(content);
        String value = "";
        while (matcher.find()) value = matcher.group(1);
        return value;
    }

    private static String precedingAnnotationBlock(String content, int offset) {
        int lineStart = content.lastIndexOf('\n', Math.max(0, offset - 1)) + 1;
        int start = lineStart;
        while (start > 0) {
            int previousEnd = start - 1;
            int previousStart = content.lastIndexOf('\n', Math.max(0, previousEnd - 1)) + 1;
            String line = content.substring(previousStart, previousEnd).strip();
            if (!line.isEmpty() && !line.startsWith("@")) {
                break;
            }
            start = previousStart;
        }
        return content.substring(start, offset);
    }

    private static int leadingSpaces(String value) {
        int count = 0;
        while (count < value.length() && Character.isWhitespace(value.charAt(count))) count++;
        return count;
    }

    private static String join(String left, String right) {
        String a = left == null ? "" : left.strip();
        String b = right == null ? "" : right.strip();
        if (a.isEmpty()) return b.isEmpty() ? "/" : b;
        if (b.isEmpty()) return a;
        return a.replaceFirst("/$", "") + "/" + b.replaceFirst("^/", "");
    }

    private static String restMethod(String operation) {
        String lower = operation.toLowerCase(Locale.ROOT);
        if (lower.startsWith("post")) return "POST";
        if (lower.equals("put")) return "PUT";
        if (lower.equals("delete")) return "DELETE";
        return "GET";
    }

    private static String pathFromUrl(String value) {
        if (value == null || value.isBlank()) return "/";
        try {
            java.net.URI uri = java.net.URI.create(value.strip());
            return uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
        } catch (IllegalArgumentException ignored) {
            return value.startsWith("/") ? value : "/" + value;
        }
    }

    private static String beforeClassDeclaration(String content) {
        if (content == null) {
            return "";
        }
        int classIdx = content.indexOf("class ");
        if (classIdx < 0) {
            classIdx = content.indexOf("interface ");
        }
        return classIdx < 0 ? content : content.substring(0, classIdx);
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
