package com.wynk.codecontext.port.contract.compat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads back the normalized signatures produced by {@code ProtoSignatures}.
 *
 * <p>The signature is the durable, JSON-safe form carried in the contract index, so comparing two
 * versions of a contract means parsing two signatures rather than re-running protoc against a
 * commit that may no longer be checked out anywhere.
 *
 * <pre>
 * message pkg.Msg{1:id:TYPE_STRING:LABEL_OPTIONAL;2:kind:TYPE_ENUM:LABEL_OPTIONAL:pkg.Kind}
 * enum pkg.Kind{0:NONE;1:SOME}
 * rpc pkg.Svc/Get(pkg.Req) returns (pkg.Res):clientStreaming=false:serverStreaming=false
 * </pre>
 */
public final class ProtoSignatureParser {

    private ProtoSignatureParser() {}

    /** A single protobuf field as recovered from a message signature. */
    public record Field(int number, String name, String type, String label, String typeName, String oneof) {
        /** Fully qualified type where applicable, else the scalar type name. */
        public String effectiveType() {
            return typeName == null || typeName.isBlank() ? type : typeName;
        }

        public boolean isRepeated() {
            return "LABEL_REPEATED".equals(label);
        }
    }

    public record Message(String qualifiedName, Map<Integer, Field> fieldsByNumber) {}

    public record EnumType(String qualifiedName, Map<Integer, String> valuesByNumber) {}

    public record Rpc(
            String qualifiedService,
            String method,
            String requestType,
            String responseType,
            boolean clientStreaming,
            boolean serverStreaming
    ) {}

    public static boolean isMessage(String signature) {
        return signature != null && signature.startsWith("message ");
    }

    public static boolean isEnum(String signature) {
        return signature != null && signature.startsWith("enum ");
    }

    public static boolean isRpc(String signature) {
        return signature != null && signature.startsWith("rpc ");
    }

    public static Message parseMessage(String signature) {
        String name = between(signature, "message ", "{");
        String body = body(signature);
        var fields = new LinkedHashMap<Integer, Field>();
        for (String record : splitRecords(body)) {
            Field field = parseField(record);
            if (field != null) {
                fields.put(field.number(), field);
            }
        }
        return new Message(name, fields);
    }

    private static Field parseField(String record) {
        String[] parts = record.split(":");
        if (parts.length < 4) {
            return null;
        }
        int number;
        try {
            number = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return null;
        }
        String typeName = null;
        String oneof = null;
        for (int i = 4; i < parts.length; i++) {
            if (parts[i].startsWith("oneof=")) {
                oneof = parts[i].substring("oneof=".length());
            } else {
                typeName = parts[i];
            }
        }
        return new Field(number, parts[1], parts[2], parts[3], typeName, oneof);
    }

    public static EnumType parseEnum(String signature) {
        String name = between(signature, "enum ", "{");
        var values = new LinkedHashMap<Integer, String>();
        for (String record : splitRecords(body(signature))) {
            int colon = record.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            try {
                values.put(Integer.parseInt(record.substring(0, colon)), record.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                // a malformed record is skipped; the surrounding diff still reports a change
            }
        }
        return new EnumType(name, values);
    }

    public static Rpc parseRpc(String signature) {
        String head = between(signature, "rpc ", "(");
        int slash = head.lastIndexOf('/');
        String service = slash < 0 ? head : head.substring(0, slash);
        String method = slash < 0 ? "" : head.substring(slash + 1);
        return new Rpc(
                service,
                method,
                between(signature, "(", ")"),
                between(signature, "returns (", ")"),
                signature.contains(":clientStreaming=true"),
                signature.contains(":serverStreaming=true"));
    }

    private static String body(String signature) {
        int open = signature.indexOf('{');
        int close = signature.lastIndexOf('}');
        return open < 0 || close <= open ? "" : signature.substring(open + 1, close);
    }

    private static String[] splitRecords(String body) {
        return body.isBlank() ? new String[0] : body.split(";");
    }

    private static String between(String value, String after, String before) {
        int start = value.indexOf(after);
        if (start < 0) {
            return "";
        }
        start += after.length();
        int end = value.indexOf(before, start);
        return end < 0 ? value.substring(start).strip() : value.substring(start, end).strip();
    }
}
