package com.wynk.codecontext.port.contract.extract;

import com.google.protobuf.DescriptorProtos.DescriptorProto;
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto;
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;
import com.wynk.codecontext.port.contract.extract.ProtoDescriptorLoader.RepoProtoFile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Protobuf message and enum contracts.
 *
 * <p>Exports are the messages and enums declared by {@code .proto} files physically present in the
 * repository, identified by fully qualified protobuf name rather than filename — nine files named
 * {@code Campaign.proto} are nine copies of one contract, and only the qualified name says so.
 *
 * <p>Imports are types referenced by those declarations but defined outside the repository.
 */

public class ProtoContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";

    /** Well-known types ship with protoc and are never a cross-repository contract. */
    private static final List<String> WELL_KNOWN_PREFIXES = List.of("google.protobuf.", "google.rpc.", "google.type.");
    /** Python protobuf generated-module convention: {@code from catalog_pb2 import Item}. */
    private static final Pattern PYTHON_GENERATED_IMPORT = Pattern.compile(
            "(?m)^\\s*from\\s+([A-Za-z_][\\w.]*)_pb2\\s+import\\s+([A-Za-z_][\\w]*(?:\\s*,\\s*[A-Za-z_][\\w]*)*)");
    /**
     * The Java generator's stable outer-class convention. Matching this is deliberately narrower
     * than treating arbitrary JVM imports as protobuf contracts: a normal Java/Kotlin DTO import
     * must not create a cross-repository edge.
     */
    private static final Pattern JVM_GENERATED_IMPORT = Pattern.compile(
            "(?m)^\\s*import\\s+((?:[A-Za-z_][\\w]*\\.)+)([A-Za-z_][\\w]*)OuterClass\\.([A-Za-z_][\\w]*)"
                    + "(?:\\s+as\\s+[A-Za-z_][\\w]*)?\\s*;?\\s*$");

    private final ProtoDescriptorLoader loader;

    public ProtoContractExtractor(ProtoDescriptorLoader loader) {
        this.loader = loader;
    }

    @Override
    public String id() {
        return ContractType.PROTO.id();
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ContractType type() {
        return ContractType.PROTO;
    }

    @Override
    public boolean available() {
        return loader.protocAvailable();
    }

    @Override
    public boolean mayDefineContract(String repoRelativePath) {
        return repoRelativePath != null && repoRelativePath.endsWith(".proto");
    }

    @Override
    public List<ContractExport> extractExports(RepositoryContext repository) {
        var exports = new ArrayList<ContractExport>();
        for (RepoProtoFile file : loader.load(repository).files()) {
            FileDescriptorProto descriptor = file.descriptor();
            String pkg = descriptor.getPackage();
            for (DescriptorProto message : descriptor.getMessageTypeList()) {
                collectMessage(pkg, message, file.sourceFile(), descriptor, exports);
            }
            for (EnumDescriptorProto enumType : descriptor.getEnumTypeList()) {
                collectEnum(pkg, enumType, file.sourceFile(), descriptor, exports);
            }
        }
        return List.copyOf(exports);
    }

    private void collectMessage(
            String scope,
            DescriptorProto message,
            String sourceFile,
            FileDescriptorProto file,
            List<ContractExport> out
    ) {
        if (ProtoSignatures.isSyntheticMapEntry(message)) {
            return;
        }
        String qualified = qualify(scope, message.getName());
        String signature = ProtoSignatures.messageSignature(qualified, message);

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("kind", "message");
        metadata.put("protoPackage", file.getPackage());
        metadata.put("fields", ProtoSignatures.fieldSummaries(message));
        metadata.put("signature", signature);

        out.add(new ContractExport(
                ContractIdentity.ofProto(file.getPackage(), stripPackage(file.getPackage(), qualified)),
                ContractType.PROTO,
                sourceFile,
                qualified,
                ProtoSignatures.contentHash(signature),
                metadata));

        // Nested declarations are independently addressable contracts (pkg.Outer.Inner).
        for (DescriptorProto nested : message.getNestedTypeList()) {
            collectMessage(qualified, nested, sourceFile, file, out);
        }
        for (EnumDescriptorProto nestedEnum : message.getEnumTypeList()) {
            collectEnum(qualified, nestedEnum, sourceFile, file, out);
        }
    }

    private void collectEnum(
            String scope,
            EnumDescriptorProto enumType,
            String sourceFile,
            FileDescriptorProto file,
            List<ContractExport> out
    ) {
        String qualified = qualify(scope, enumType.getName());
        String signature = ProtoSignatures.enumSignature(qualified, enumType);

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("kind", "enum");
        metadata.put("protoPackage", file.getPackage());
        metadata.put("values", enumType.getValueList().stream()
                .map(v -> v.getNumber() + " " + v.getName()).toList());
        metadata.put("signature", signature);

        out.add(new ContractExport(
                ContractIdentity.ofProto(file.getPackage(), stripPackage(file.getPackage(), qualified)),
                ContractType.PROTO,
                sourceFile,
                qualified,
                ProtoSignatures.contentHash(signature),
                metadata));
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        ProtoDescriptorLoader.Result result = loader.load(repository);
        Set<String> declaredInRepo = declaredTypeNames(result.files());

        var imports = new LinkedHashMap<String, ContractImport>();
        for (RepoProtoFile file : result.files()) {
            for (DescriptorProto message : file.descriptor().getMessageTypeList()) {
                collectReferencedTypes(message, file, declaredInRepo, imports);
            }
        }
        // Generated Python clients frequently consume a producer without carrying its .proto
        // source. The generated module name is a language-defined contract mapping, unlike a
        // generic source-text symbol search.
        for (var source : ExtractorFileSupport.read(repository, Set.of(".py"), 2L * 1024 * 1024)) {
            var matcher = PYTHON_GENERATED_IMPORT.matcher(source.content());
            while (matcher.find()) {
                String module = matcher.group(1).replace('_', '.');
                for (String type : matcher.group(2).split(",")) {
                    String name = type.strip();
                    if (!name.isBlank()) {
                        ContractIdentity identity = ContractIdentity.ofProto(module, name);
                        imports.putIfAbsent(identity.canonicalKey() + "|" + source.path(), new ContractImport(
                                identity, ContractType.PROTO, source.path(), name, null,
                                Map.of("via", "python-generated-protobuf")));
                    }
                }
            }
        }
        // Java and Kotlin use the same import form. Only the protobuf generator's OuterClass
        // convention is accepted here, which makes the package/type mapping deterministic.
        for (var source : ExtractorFileSupport.read(repository, Set.of(".java", ".kt"), 2L * 1024 * 1024)) {
            var matcher = JVM_GENERATED_IMPORT.matcher(source.content());
            while (matcher.find()) {
                String namespace = matcher.group(1).substring(0, matcher.group(1).length() - 1);
                String type = matcher.group(3);
                ContractIdentity identity = ContractIdentity.ofProto(namespace, type);
                imports.putIfAbsent(identity.canonicalKey() + "|" + source.path(), new ContractImport(
                        identity, ContractType.PROTO, source.path(), matcher.group(0).strip(), null,
                        Map.of("via", "jvm-generated-protobuf", "outerClass", matcher.group(2))));
            }
        }
        return List.copyOf(imports.values());
    }

    private void collectReferencedTypes(
            DescriptorProto message,
            RepoProtoFile file,
            Set<String> declaredInRepo,
            Map<String, ContractImport> out
    ) {
        for (FieldDescriptorProto field : message.getFieldList()) {
            String referenced = ProtoSignatures.stripLeadingDot(field.getTypeName());
            if (!isExternalContract(referenced, declaredInRepo)) {
                continue;
            }
            ContractIdentity identity = identityFor(referenced);
            out.putIfAbsent(identity.canonicalKey() + "|" + file.sourceFile(), new ContractImport(
                    identity,
                    ContractType.PROTO,
                    file.sourceFile(),
                    referenced,
                    null,
                    Map.of("referencedBy", message.getName(), "field", field.getName())));
        }
        for (DescriptorProto nested : message.getNestedTypeList()) {
            if (!ProtoSignatures.isSyntheticMapEntry(nested)) {
                collectReferencedTypes(nested, file, declaredInRepo, out);
            }
        }
    }

    /** Fully qualified names of every message and enum declared by in-repo proto files. */
    static Set<String> declaredTypeNames(List<RepoProtoFile> files) {
        var names = new LinkedHashSet<String>();
        for (RepoProtoFile file : files) {
            String pkg = file.descriptor().getPackage();
            for (DescriptorProto message : file.descriptor().getMessageTypeList()) {
                addMessageNames(pkg, message, names);
            }
            for (EnumDescriptorProto enumType : file.descriptor().getEnumTypeList()) {
                names.add(qualify(pkg, enumType.getName()));
            }
        }
        return names;
    }

    private static void addMessageNames(String scope, DescriptorProto message, Set<String> names) {
        String qualified = qualify(scope, message.getName());
        names.add(qualified);
        for (DescriptorProto nested : message.getNestedTypeList()) {
            addMessageNames(qualified, nested, names);
        }
        for (EnumDescriptorProto nestedEnum : message.getEnumTypeList()) {
            names.add(qualify(qualified, nestedEnum.getName()));
        }
    }

    static boolean isExternalContract(String typeName, Set<String> declaredInRepo) {
        if (typeName == null || typeName.isEmpty() || declaredInRepo.contains(typeName)) {
            return false;
        }
        return WELL_KNOWN_PREFIXES.stream().noneMatch(typeName::startsWith);
    }

    /** Splits {@code pkg.Outer.Inner} into a package and a (possibly nested) type name. */
    static ContractIdentity identityFor(String qualifiedTypeName) {
        int lastDot = qualifiedTypeName.lastIndexOf('.');
        if (lastDot < 0) {
            return ContractIdentity.ofProto(null, qualifiedTypeName);
        }
        return ContractIdentity.ofProto(
                qualifiedTypeName.substring(0, lastDot), qualifiedTypeName.substring(lastDot + 1));
    }

    private static String qualify(String scope, String name) {
        return scope == null || scope.isEmpty() ? name : scope + "." + name;
    }

    /** Turns {@code pkg.Outer.Inner} back into {@code Outer.Inner} for identity construction. */
    private static String stripPackage(String protoPackage, String qualified) {
        if (protoPackage == null || protoPackage.isEmpty()) {
            return qualified;
        }
        String prefix = protoPackage + ".";
        return qualified.startsWith(prefix) ? qualified.substring(prefix.length()) : qualified;
    }
}
