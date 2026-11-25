package com.wynk.codecontext.port.contract.compat;

import com.wynk.codecontext.port.contract.compat.CompatibilityModels.Compatibility;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChangeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Direction-aware field comparison for structured REST and Kafka schema signatures.
 *
 * <p>Request-side and response-side additions/removals are not treated the same: adding a required
 * request field breaks existing clients, while adding a response field does not. Removals invert.
 * Type changes are breaking on either side. Unmodelled signature shapes stay UNKNOWN.
 */
final class StructuredSchemaCompat {

    private StructuredSchemaCompat() {}

    static boolean isRestOperation(String signature) {
        return signature != null && signature.startsWith("restOp{");
    }

    static boolean isFieldSchema(String signature) {
        return signature != null && signature.startsWith("fields{");
    }

    record Diff(ContractChangeType changeType, Compatibility compatibility, String detail) {}

    static List<Diff> compareRest(String beforeSignature, String afterSignature) {
        RestOp beforeOp = parseRestOp(beforeSignature);
        RestOp afterOp = parseRestOp(afterSignature);
        if (beforeOp == null || afterOp == null) {
            return List.of();
        }
        var changes = new ArrayList<Diff>();
        changes.addAll(compareNamedFields(
                beforeOp.params(), afterOp.params(), Direction.REQUEST,
                ContractChangeType.ENDPOINT_SIGNATURE_CHANGED, "parameter"));
        changes.addAll(compareNamedFields(
                beforeOp.request(), afterOp.request(), Direction.REQUEST,
                ContractChangeType.ENDPOINT_SIGNATURE_CHANGED, "request field"));
        changes.addAll(compareNamedFields(
                beforeOp.response(), afterOp.response(), Direction.RESPONSE,
                ContractChangeType.ENDPOINT_SIGNATURE_CHANGED, "response field"));
        if (changes.isEmpty() && !beforeSignature.equals(afterSignature)) {
            changes.add(new Diff(ContractChangeType.ENDPOINT_SIGNATURE_CHANGED, Compatibility.UNKNOWN,
                    "REST operation changed without a modelled field-level delta."));
        }
        return List.copyOf(changes);
    }

    static List<Diff> compareFields(String beforeSignature, String afterSignature, Direction direction) {
        Map<String, Field> beforeFields = parseFields(beforeSignature);
        Map<String, Field> afterFields = parseFields(afterSignature);
        if (beforeFields == null || afterFields == null) {
            return List.of();
        }
        return compareNamedFields(
                beforeFields, afterFields, direction,
                ContractChangeType.SCHEMA_CHANGED, "field");
    }

    private static List<Diff> compareNamedFields(
            Map<String, Field> beforeFields,
            Map<String, Field> afterFields,
            Direction direction,
            ContractChangeType changeType,
            String noun
    ) {
        var changes = new ArrayList<Diff>();
        var names = new TreeSet<String>();
        names.addAll(beforeFields.keySet());
        names.addAll(afterFields.keySet());
        for (String name : names) {
            Field beforeField = beforeFields.get(name);
            Field afterField = afterFields.get(name);
            if (beforeField == null) {
                Compatibility compatibility = afterField.required() && direction == Direction.REQUEST
                        ? Compatibility.BREAKING
                        : Compatibility.BACKWARD_COMPATIBLE;
                changes.add(new Diff(changeType, compatibility,
                        noun + " '" + name + "' added as " + afterField.cardinality()
                                + " (" + direction.label() + "); "
                                + (compatibility == Compatibility.BREAKING
                                ? "existing clients must supply it."
                                : "existing consumers remain compatible.")));
            } else if (afterField == null) {
                Compatibility compatibility = direction == Direction.RESPONSE
                        ? Compatibility.BREAKING
                        : Compatibility.BACKWARD_COMPATIBLE;
                changes.add(new Diff(changeType, compatibility,
                        noun + " '" + name + "' removed (" + direction.label() + "); "
                                + (compatibility == Compatibility.BREAKING
                                ? "consumers reading it lose data."
                                : "clients may keep sending it; servers ignore unknown fields.")));
            } else if (!Objects.equals(beforeField.type(), afterField.type())) {
                changes.add(new Diff(changeType, Compatibility.BREAKING,
                        noun + " '" + name + "' type changed from " + beforeField.type()
                                + " to " + afterField.type() + " (" + direction.label() + ")."));
            } else if (beforeField.required() != afterField.required()) {
                Compatibility compatibility = !beforeField.required() && afterField.required()
                        && direction == Direction.REQUEST
                        ? Compatibility.BREAKING
                        : Compatibility.BACKWARD_COMPATIBLE;
                changes.add(new Diff(changeType, compatibility,
                        noun + " '" + name + "' cardinality changed from " + beforeField.cardinality()
                                + " to " + afterField.cardinality() + " (" + direction.label() + ")."));
            }
        }
        return changes;
    }

    private static RestOp parseRestOp(String signature) {
        if (!isRestOperation(signature)) return null;
        String body = signature.substring("restOp{".length(), signature.endsWith("}") ? signature.length() - 1 : signature.length());
        Map<String, Field> params = Map.of();
        Map<String, Field> request = Map.of();
        Map<String, Field> response = Map.of();
        for (String section : body.split(";", -1)) {
            int eq = section.indexOf('=');
            if (eq <= 0) continue;
            String key = section.substring(0, eq);
            String value = section.substring(eq + 1);
            switch (key) {
                case "params" -> params = parseParamFields(value);
                case "request" -> request = parseObjectFields(value);
                case "response" -> response = parseObjectFields(value);
                default -> { }
            }
        }
        return new RestOp(params, request, response);
    }

    private static Map<String, Field> parseFields(String signature) {
        if (!isFieldSchema(signature)) return null;
        String body = signature.substring("fields{".length(), signature.endsWith("}") ? signature.length() - 1 : signature.length());
        return parseObjectFields(body);
    }

    private static Map<String, Field> parseParamFields(String body) {
        var fields = new LinkedHashMap<String, Field>();
        if (body == null || body.isBlank()) return fields;
        for (String record : body.split(",")) {
            String[] parts = record.split(":");
            if (parts.length < 4) continue;
            String key = parts[0] + ":" + parts[1];
            fields.put(key, new Field(key, parts[3], "required".equalsIgnoreCase(parts[2])));
        }
        return fields;
    }

    private static Map<String, Field> parseObjectFields(String body) {
        var fields = new LinkedHashMap<String, Field>();
        if (body == null || body.isBlank()) return fields;
        for (String record : body.split(",")) {
            String[] parts = record.split(":");
            if (parts.length < 3) continue;
            fields.put(parts[0], new Field(parts[0], parts[1], "required".equalsIgnoreCase(parts[2])));
        }
        return fields;
    }

    enum Direction {
        REQUEST, RESPONSE;

        String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private record Field(String name, String type, boolean required) {
        String cardinality() {
            return required ? "required" : "optional";
        }
    }

    private record RestOp(Map<String, Field> params, Map<String, Field> request, Map<String, Field> response) {}
}
