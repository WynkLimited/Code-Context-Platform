package com.wynk.codecontext.port.contract.extract;

import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.MethodDescriptorProto;
import com.google.protobuf.DescriptorProtos.ServiceDescriptorProto;
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
import java.util.Set;

/**
 * gRPC service contracts, one per RPC method.
 *
 * <p>Method-level rather than service-level identity, because {@code GetCampaign} changing its
 * response type affects only its callers, while service-level identity would implicate every
 * consumer of every method. Service-level changes such as a rename are still detectable: the
 * service's fully qualified name is carried in each export's metadata for the compatibility engine
 * to group on.
 *
 * <p>Imports are RPCs declared in proto files the repository pulls in from outside itself. A
 * consumer that depends only on a generated stub jar — with no {@code .proto} of its own — is not
 * visible to this extractor; that case is covered by the Maven extractor in Phase C.
 */

public class GrpcContractExtractor implements ContractExtractor {

    public static final String VERSION = "1.0.0";

    private final ProtoDescriptorLoader loader;

    public GrpcContractExtractor(ProtoDescriptorLoader loader) {
        this.loader = loader;
    }

    @Override
    public String id() {
        return ContractType.GRPC.id();
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public ContractType type() {
        return ContractType.GRPC;
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
            for (ServiceDescriptorProto service : descriptor.getServiceList()) {
                String qualifiedService = qualify(descriptor.getPackage(), service.getName());
                for (MethodDescriptorProto method : service.getMethodList()) {
                    exports.add(exportFor(descriptor, service, qualifiedService, method, file.sourceFile()));
                }
            }
        }
        return List.copyOf(exports);
    }

    private static ContractExport exportFor(
            FileDescriptorProto descriptor,
            ServiceDescriptorProto service,
            String qualifiedService,
            MethodDescriptorProto method,
            String sourceFile
    ) {
        String signature = ProtoSignatures.methodSignature(qualifiedService, method);

        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("kind", "rpc");
        metadata.put("protoPackage", descriptor.getPackage());
        metadata.put("service", qualifiedService);
        metadata.put("requestType", ProtoSignatures.stripLeadingDot(method.getInputType()));
        metadata.put("responseType", ProtoSignatures.stripLeadingDot(method.getOutputType()));
        metadata.put("clientStreaming", method.getClientStreaming());
        metadata.put("serverStreaming", method.getServerStreaming());
        metadata.put("signature", signature);

        return new ContractExport(
                ContractIdentity.ofGrpc(descriptor.getPackage(), service.getName(), method.getName()),
                ContractType.GRPC,
                sourceFile,
                qualifiedService + "/" + method.getName(),
                ProtoSignatures.contentHash(signature),
                metadata);
    }

    @Override
    public List<ContractImport> extractImports(RepositoryContext repository) {
        ProtoDescriptorLoader.Result result = loader.load(repository);
        if (result.isEmpty()) {
            return List.of();
        }
        Set<String> inRepoFiles = new LinkedHashSet<>();
        Set<String> directDependencies = new LinkedHashSet<>();
        for (RepoProtoFile file : result.files()) {
            inRepoFiles.add(file.descriptor().getName());
            directDependencies.addAll(file.descriptor().getDependencyList());
        }

        var imports = new ArrayList<ContractImport>();
        for (String dependency : directDependencies) {
            if (inRepoFiles.contains(dependency)) {
                continue;
            }
            FileDescriptorProto descriptor = result.dependencies().get(dependency);
            if (descriptor == null || descriptor.getServiceCount() == 0) {
                continue;
            }
            for (ServiceDescriptorProto service : descriptor.getServiceList()) {
                for (MethodDescriptorProto method : service.getMethodList()) {
                    imports.add(new ContractImport(
                            ContractIdentity.ofGrpc(descriptor.getPackage(), service.getName(), method.getName()),
                            ContractType.GRPC,
                            dependency,
                            qualify(descriptor.getPackage(), service.getName()) + "/" + method.getName(),
                            null,
                            java.util.Map.of("via", "proto-import")));
                }
            }
        }
        return List.copyOf(imports);
    }

    private static String qualify(String protoPackage, String name) {
        return protoPackage == null || protoPackage.isEmpty() ? name : protoPackage + "." + name;
    }
}
