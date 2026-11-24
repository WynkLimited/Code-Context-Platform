package com.wynk.codecontext.port.contract.extract;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Normalized, order-independent signatures for protobuf declarations.
 *
 * <p>A signature captures exactly the properties that can break a consumer — field numbers, types,
 * cardinality, {@code oneof} membership, enum values, RPC request/response types — and nothing
 * else. Comments, formatting, declaration order and surrounding file content are excluded, so
 * reformatting a {@code .proto} or editing a comment leaves the hash untouched while renumbering a
 * field changes it.
 */
public final class ProtoSignatures {

    private ProtoSignatures() {}

    /** Signature of a message: its fields, sorted by field number. */
    public static String messageSignature(String qualifiedName, DescriptorProto message) {
        String fields = message.getFieldList().stream()
                .sorted(Comparator.comparingInt(FieldDescriptorProto::getNumber))
                .map(f -> fieldSignature(message, f))
                .collect(Collectors.joining(";"));
        return "message " + qualifiedName + "{" + fields + "}";
    }

    private static String fieldSignature(DescriptorProto message, FieldDescriptorProto field) {
        var sb = new StringBuilder()
                .append(field.getNumber()).append(':')
                .append(field.getName()).append(':')
                .append(field.getType().name()).append(':')
                .append(field.getLabel().name());
        if (!field.getTypeName().isEmpty()) {
            sb.append(':').append(stripLeadingDot(field.getTypeName()));
        }
        // Moving a field into or out of a oneof changes wire and generated-API semantics.
        if (field.hasOneofIndex() && field.getOneofIndex() < message.getOneofDeclCount()) {
            sb.append(":oneof=").append(message.getOneofDecl(field.getOneofIndex()).getName());
        }
        return sb.toString();
    }

    /** Signature of an enum: its values, sorted by number. */
    public static String enumSignature(String qualifiedName, EnumDescriptorProto enumType) {
        String values = enumType.getValueList().stream()
                .sorted(Comparator.comparingInt(EnumValueDescriptorProto::getNumber))
                .map(v -> v.getNumber() + ":" + v.getName())
                .collect(Collectors.joining(";"));
        return "enum " + qualifiedName + "{" + values + "}";
    }

    /** Signature of a single RPC, including streaming cardinality. */
    public static String methodSignature(String qualifiedService, MethodDescriptorProto method) {
        return "rpc " + qualifiedService + "/" + method.getName()
                + "(" + stripLeadingDot(method.getInputType()) + ")"
                + " returns (" + stripLeadingDot(method.getOutputType()) + ")"
                + ":clientStreaming=" + method.getClientStreaming()
                + ":serverStreaming=" + method.getServerStreaming();
    }

    /**
     * True for the synthetic {@code XEntry} messages protoc generates for {@code map<K,V>} fields.
     * They are an encoding detail, not a contract anyone can depend on by name.
     */
    public static boolean isSyntheticMapEntry(DescriptorProto message) {
        return message.hasOptions() && message.getOptions().getMapEntry();
    }

    /** Protobuf type names arrive fully qualified with a leading dot ({@code .pkg.Msg}). */
    public static String stripLeadingDot(String typeName) {
        if (typeName == null || typeName.isEmpty()) {
            return "";
        }
        return typeName.startsWith(".") ? typeName.substring(1) : typeName;
    }

    /** {@code sha256:<hex>} over the UTF-8 signature. */
    public static String contentHash(String signature) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of()
                    .formatHex(digest.digest(signature.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Field summary retained in export metadata for compatibility analysis and prompts. */
    public static List<String> fieldSummaries(DescriptorProto message) {
        return message.getFieldList().stream()
                .sorted(Comparator.comparingInt(FieldDescriptorProto::getNumber))
                .map(f -> f.getNumber() + " " + f.getName() + " "
                        + f.getType().name().replace("TYPE_", "").toLowerCase(java.util.Locale.ROOT)
                        + (f.getLabel() == FieldDescriptorProto.Label.LABEL_REPEATED ? " repeated" : ""))
                .toList();
    }
}
