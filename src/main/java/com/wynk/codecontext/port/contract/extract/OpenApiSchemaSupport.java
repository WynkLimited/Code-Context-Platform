package com.wynk.codecontext.port.contract.extract;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Normalizes OpenAPI operations into a durable, order-independent signature used by
 * {@code ContractCompatibilityEngine} for direction-aware request/response comparison.
 *
 * <p>Signature form:
 * {@code restOp{params=in:name:required|optional:type;...;request=name:type:required|optional;...;response=...}}
 *
 * <p>{@code $ref} values under {@code #/components/schemas/} and same-document anchors are
 * resolved with a bounded depth. Relative file refs ({@code schemas/foo.yaml#/...}) are resolved
 * when a {@link DocumentLoader} is supplied. Unresolved refs remain opaque type tokens.
 */
final class OpenApiSchemaSupport {

    private static final int MAX_REF_DEPTH = 6;
    private static final int MAX_FIELDS = 128;

    @FunctionalInterface
    interface DocumentLoader {
        JsonNode load(String relativePath);
    }

    private OpenApiSchemaSupport() {}

    static String operationSignature(JsonNode document, JsonNode operation) {
        return operationSignature(document, operation, null);
    }

    static String operationSignature(JsonNode document, JsonNode operation, DocumentLoader loader) {
        if (operation == null || operation.isMissingNode() || operation.isNull()) {
            return "";
        }
        Map<String, String> params = new TreeMap<>();
        collectParameters(document, operation.path("parameters"), params, 0, loader);
        Map<String, String> request = objectFields(document, requestSchema(operation), 0, loader);
        Map<String, String> response = objectFields(document, primaryResponseSchema(operation), 0, loader);
        return "restOp{params=" + join(params) + ";request=" + join(request) + ";response=" + join(response) + "}";
    }

    static Map<String, Object> operationEvidence(JsonNode document, JsonNode operation) {
        return operationEvidence(document, operation, null);
    }

    static Map<String, Object> operationEvidence(JsonNode document, JsonNode operation, DocumentLoader loader) {
        var evidence = new LinkedHashMap<String, Object>();
        if (operation == null || operation.isMissingNode()) {
            return Map.copyOf(evidence);
        }
        Map<String, String> params = new TreeMap<>();
        collectParameters(document, operation.path("parameters"), params, 0, loader);
        Map<String, String> request = objectFields(document, requestSchema(operation), 0, loader);
        Map<String, String> response = objectFields(document, primaryResponseSchema(operation), 0, loader);
        if (!params.isEmpty()) evidence.put("parameters", List.copyOf(params.values()));
        if (!request.isEmpty()) evidence.put("requestSchema", List.copyOf(request.values()));
        if (!response.isEmpty()) evidence.put("responseSchema", List.copyOf(response.values()));
        boolean unresolvedType = params.values().stream().anyMatch(v -> v.contains(":ref:"))
                || request.values().stream().anyMatch(v -> v.contains(":ref:"))
                || response.values().stream().anyMatch(v -> v.contains(":ref:"));
        evidence.put("schemaComplete", !unresolvedType && (!params.isEmpty() || !request.isEmpty() || !response.isEmpty()));
        return Map.copyOf(evidence);
    }

    private static JsonNode requestSchema(JsonNode operation) {
        JsonNode content = operation.path("requestBody").path("content");
        if (content.isObject()) {
            JsonNode appJson = content.path("application/json").path("schema");
            if (!appJson.isMissingNode()) return appJson;
            for (JsonNode media : content) {
                JsonNode schema = media.path("schema");
                if (!schema.isMissingNode()) return schema;
            }
        }
        return operation.path("requestBody").path("schema");
    }

    private static JsonNode primaryResponseSchema(JsonNode operation) {
        JsonNode responses = operation.path("responses");
        if (!responses.isObject()) return responses.path("missing");
        for (String code : List.of("200", "201", "202", "204", "default")) {
            JsonNode schema = schemaFromResponse(responses.path(code));
            if (!schema.isMissingNode() && !schema.isNull()) return schema;
        }
        for (var entry : responses.properties()) {
            JsonNode schema = schemaFromResponse(entry.getValue());
            if (!schema.isMissingNode() && !schema.isNull()) return schema;
        }
        return responses.path("missing");
    }

    private static JsonNode schemaFromResponse(JsonNode response) {
        if (response == null || response.isMissingNode()) return response;
        JsonNode content = response.path("content");
        if (content.isObject()) {
            JsonNode appJson = content.path("application/json").path("schema");
            if (!appJson.isMissingNode()) return appJson;
            for (JsonNode media : content) {
                JsonNode schema = media.path("schema");
                if (!schema.isMissingNode()) return schema;
            }
        }
        return response.path("schema");
    }

    private static void collectParameters(JsonNode document, JsonNode parameters, Map<String, String> out, int depth,
                                          DocumentLoader loader) {
        if (parameters == null || !parameters.isArray() || depth > MAX_REF_DEPTH) return;
        for (JsonNode parameter : parameters) {
            JsonNode resolved = resolveRef(document, parameter, depth, loader);
            if (resolved == null || resolved.isMissingNode()) continue;
            String in = text(resolved, "in");
            String name = text(resolved, "name");
            if (in.isBlank() || name.isBlank()) continue;
            boolean required = resolved.path("required").asBoolean("path".equalsIgnoreCase(in));
            String type = schemaType(document, resolved.path("schema"), depth + 1, loader);
            out.put(in.toLowerCase(Locale.ROOT) + ":" + name,
                    in.toLowerCase(Locale.ROOT) + ":" + name + ":" + (required ? "required" : "optional") + ":" + type);
            if (out.size() >= MAX_FIELDS) return;
        }
    }

    private static Map<String, String> objectFields(JsonNode document, JsonNode schema, int depth) {
        return objectFields(document, schema, depth, null);
    }

    private static Map<String, String> objectFields(JsonNode document, JsonNode schema, int depth, DocumentLoader loader) {
        var fields = new TreeMap<String, String>();
        JsonNode resolved = resolveRef(document, schema, depth, loader);
        if (resolved == null || resolved.isMissingNode() || resolved.isNull()) return fields;
        if (resolved.has("allOf") && resolved.path("allOf").isArray()) {
            for (JsonNode part : resolved.path("allOf")) {
                fields.putAll(objectFields(document, part, depth + 1, loader));
                if (fields.size() >= MAX_FIELDS) return fields;
            }
            return fields;
        }
        JsonNode properties = resolved.path("properties");
        if (!properties.isObject()) {
            String type = schemaType(document, resolved, depth, loader);
            if (!type.isBlank() && !"object".equals(type)) {
                fields.put("$", "$:" + type + ":optional");
            }
            return fields;
        }
        Set<String> required = new LinkedHashSet<>();
        if (resolved.path("required").isArray()) {
            for (JsonNode name : resolved.path("required")) {
                if (name.isString()) required.add(name.asText());
            }
        }
        for (var property : properties.properties()) {
            String name = property.getKey();
            JsonNode nested = resolveRef(document, property.getValue(), depth + 1, loader);
            Map<String, String> nestedFields = objectFields(document, nested, depth + 1, loader);
            boolean isRequired = required.contains(name);
            if (!nestedFields.isEmpty() && nestedFields.keySet().stream().noneMatch("$"::equals)) {
                // Flatten nested object/$ref properties so schema evolution sees leaf fields.
                for (var nestedEntry : nestedFields.entrySet()) {
                    String nestedName = name + "." + nestedEntry.getKey();
                    String[] parts = nestedEntry.getValue().split(":", 3);
                    String nestedType = parts.length > 1 ? parts[1] : "object";
                    String nestedReq = parts.length > 2 ? parts[2] : "optional";
                    // Nested required only applies when the parent property itself is required.
                    String effectiveReq = isRequired ? nestedReq : "optional";
                    fields.put(nestedName, nestedName + ":" + nestedType + ":" + effectiveReq);
                    if (fields.size() >= MAX_FIELDS) return fields;
                }
            } else {
                String type = schemaType(document, property.getValue(), depth + 1, loader);
                fields.put(name, name + ":" + type + ":" + (isRequired ? "required" : "optional"));
                if (fields.size() >= MAX_FIELDS) break;
            }
        }
        return fields;
    }

    private static String schemaType(JsonNode document, JsonNode schema, int depth, DocumentLoader loader) {
        JsonNode resolved = resolveRef(document, schema, depth, loader);
        if (resolved == null || resolved.isMissingNode() || resolved.isNull()) {
            String ref = schema != null ? text(schema, "$ref") : "";
            return ref.isBlank() ? "unknown" : "ref:" + refLeaf(ref);
        }
        if (resolved.path("enum").isArray() && !resolved.path("enum").isEmpty()) {
            return "enum";
        }
        String type = text(resolved, "type");
        if (type.isBlank() && resolved.path("properties").isObject()) type = "object";
        if ("array".equals(type)) {
            return "array<" + schemaType(document, resolved.path("items"), depth + 1, loader) + ">";
        }
        String format = text(resolved, "format");
        if (!type.isBlank() && !format.isBlank()) return type + "/" + format;
        if (!type.isBlank()) return type;
        String ref = text(schema, "$ref");
        return ref.isBlank() ? "object" : "ref:" + refLeaf(ref);
    }

    private static JsonNode resolveRef(JsonNode document, JsonNode node, int depth, DocumentLoader loader) {
        if (node == null || node.isMissingNode() || node.isNull()) return node;
        String ref = text(node, "$ref");
        if (ref.isBlank()) return node;
        if (depth >= MAX_REF_DEPTH || document == null) return node;
        JsonNode root = document;
        JsonNode current = document;
        String pointer = ref;
        if (!ref.startsWith("#")) {
            if (loader == null) return node;
            int hash = ref.indexOf('#');
            String file = hash < 0 ? ref : ref.substring(0, hash);
            pointer = hash < 0 ? "" : ref.substring(hash);
            if (file.isBlank() || file.contains("..") || file.startsWith("/") || file.contains(":")) return node;
            JsonNode external = loader.load(file);
            if (external == null || external.isMissingNode() || external.isNull()) return node;
            root = external;
            current = external;
        }
        if (pointer.isBlank() || "#".equals(pointer)) return current;
        if (!pointer.startsWith("#/")) return node;
        for (String part : pointer.substring(2).split("/")) {
            if (part.isBlank()) continue;
            current = current.path(part.replace("~1", "/").replace("~0", "~"));
            if (current.isMissingNode()) return node;
        }
        if (current.has("$ref")) {
            return resolveRef(root, current, depth + 1, loader);
        }
        return current;
    }

    private static String join(Map<String, String> values) {
        if (values.isEmpty()) return "";
        List<String> ordered = new ArrayList<>(values.values());
        ordered.sort(Comparator.naturalOrder());
        return String.join(",", ordered);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) return "";
        JsonNode value = node.path(field);
        return value.isString() || value.isNumber() || value.isBoolean() ? value.asText().strip() : "";
    }

    private static String refLeaf(String ref) {
        int slash = ref.lastIndexOf('/');
        return slash < 0 ? ref : ref.substring(slash + 1);
    }
}
