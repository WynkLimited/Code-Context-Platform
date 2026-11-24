package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.RepositoryContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf("com.wynk.codecontext.port.contract.extract.ProtoContractExtractorTest#protocAvailable")
class ProtoContractExtractorTest {

    @TempDir
    Path tempDir;

    private ProtoDescriptorLoader loader;
    private ProtoContractExtractor proto;
    private GrpcContractExtractor grpc;

    static boolean protocAvailable() {
        return ProtoExtractorTestSupport.protocAvailable();
    }

    @BeforeEach
    void setUp() {
        loader = new ProtoDescriptorLoader();
        proto = new ProtoContractExtractor(loader);
        grpc = new GrpcContractExtractor(loader);
    }

    private RepositoryContext repo(Map<String, String> files) throws IOException {
        return ProtoExtractorTestSupport.repo(tempDir, files);
    }

    private static List<String> keys(List<ContractExport> exports) {
        return exports.stream().map(ContractExport::canonicalKey).sorted().toList();
    }

    @Test
    void extractsMessagesEnumsAndRpcsByFullyQualifiedName() throws IOException {
        var repository = repo(Map.of("src/main/proto/Campaign.proto", ProtoExtractorTestSupport.CAMPAIGN_PROTO));

        List<ContractExport> protoExports = proto.extractExports(repository);
        List<ContractExport> grpcExports = grpc.extractExports(repository);

        assertEquals(List.of(
                        "proto:in.wynk.userGrowth.Campaign",
                        "proto:in.wynk.userGrowth.GetCampaignRequest",
                        "proto:in.wynk.userGrowth.GetCampaignResponse",
                        "proto:in.wynk.userGrowth.Realm"),
                keys(protoExports));
        assertEquals(List.of("grpc:in.wynk.userGrowth.CampaignService/GetCampaign"), keys(grpcExports));
        assertTrue(protoExports.stream().allMatch(e -> e.type() == ContractType.PROTO));
        assertEquals("src/main/proto/Campaign.proto", protoExports.get(0).sourceFile());
    }

    @Test
    void rpcMetadataCarriesServiceAndSignatureTypes() throws IOException {
        var repository = repo(Map.of("src/main/proto/Campaign.proto", ProtoExtractorTestSupport.CAMPAIGN_PROTO));

        ContractExport rpc = grpc.extractExports(repository).get(0);

        assertEquals("in.wynk.userGrowth.CampaignService", rpc.metadata().get("service"));
        assertEquals("in.wynk.userGrowth.GetCampaignRequest", rpc.metadata().get("requestType"));
        assertEquals("in.wynk.userGrowth.GetCampaignResponse", rpc.metadata().get("responseType"));
        assertEquals(false, rpc.metadata().get("serverStreaming"));
    }

    @Test
    void identityIsDrivenByPackageNotFilename() throws IOException {
        var repository = repo(Map.of(
                "moduleA/src/main/proto/Campaign.proto", """
                        syntax = "proto3";
                        package in.wynk.userGrowth;
                        message Campaign { string id = 1; }
                        """,
                "moduleB/src/main/proto/Totally-Different-Name.proto", """
                        syntax = "proto3";
                        package in.wynk.userGrowth;
                        message Campaign { string id = 1; }
                        """));

        List<ContractExport> exports = proto.extractExports(repository);

        assertEquals(2, exports.size(), "both copies are recorded, with distinct source files");
        assertTrue(exports.stream().allMatch(e -> e.canonicalKey().equals("proto:in.wynk.userGrowth.Campaign")),
                "filename is irrelevant to contract identity");
        assertEquals(exports.get(0).contentHash(), exports.get(1).contentHash(),
                "identical contracts hash identically regardless of file name");
    }

    @Test
    void vendoredCopyDriftShowsAsDifferingContentHashForTheSameKey() throws IOException {
        var repository = repo(Map.of(
                "producer/src/main/proto/Campaign.proto", """
                        syntax = "proto3";
                        package in.wynk.userGrowth;
                        message Campaign { string id = 1; string name = 2; }
                        """,
                "consumer/src/main/proto/Campaign.proto", """
                        syntax = "proto3";
                        package in.wynk.userGrowth;
                        message Campaign { string id = 1; }
                        """));

        List<String> hashes = proto.extractExports(repository).stream()
                .filter(e -> e.canonicalKey().equals("proto:in.wynk.userGrowth.Campaign"))
                .map(ContractExport::contentHash)
                .distinct()
                .toList();

        assertEquals(2, hashes.size(), "a stale vendored copy must be visible as hash divergence");
    }

    @Test
    void generatedJavaAndKotlinOuterClassImportsBecomeProtoImports() throws IOException {
        var repository = repo(Map.of(
                "src/main/java/in/wynk/HermesMapper.java", """
                        package in.wynk;
                        import in.wynk.userGrowth.CampaignOuterClass.Campaign;
                        class HermesMapper { Campaign map() { return null; } }
                        """,
                "src/main/kotlin/in/wynk/CampaignConsumer.kt", """
                        package in.wynk
                        import in.wynk.userGrowth.CampaignOuterClass.Campaign as CampaignProto
                        class CampaignConsumer(val campaign: CampaignProto)
                        """,
                "src/main/java/in/wynk/NormalDto.java", """
                        import in.wynk.userGrowth.Campaign;
                        class NormalDto { Campaign campaign; }
                        """));

        List<ContractImport> imports = proto.extractImports(repository);

        assertEquals(2, imports.size());
        assertTrue(imports.stream().allMatch(i -> i.canonicalKey().equals("proto:in.wynk.userGrowth.Campaign")));
        assertEquals(List.of("src/main/java/in/wynk/HermesMapper.java", "src/main/kotlin/in/wynk/CampaignConsumer.kt"),
                imports.stream().map(ContractImport::sourceFile).sorted().toList());
    }

    @Test
    void nineIdenticallyNamedProtosDoNotCrossContaminate() throws IOException {
        var files = new java.util.LinkedHashMap<String, String>();
        for (int i = 1; i <= 9; i++) {
            files.put("module" + i + "/src/main/proto/Campaign.proto", """
                    syntax = "proto3";
                    package in.wynk.module%d;
                    message Campaign { string id = 1; }
                    """.formatted(i));
        }

        List<String> exportKeys = keys(proto.extractExports(repo(files)));

        assertEquals(9, exportKeys.size());
        for (int i = 1; i <= 9; i++) {
            assertTrue(exportKeys.contains("proto:in.wynk.module" + i + ".Campaign"),
                    () -> "each module's own package must be preserved, got " + exportKeys);
        }
    }

    @Test
    void nestedMessagesAndEnumsAreIndependentContracts() throws IOException {
        var repository = repo(Map.of("a.proto", """
                syntax = "proto3";
                package in.wynk;
                message Outer {
                  message Inner { string v = 1; }
                  enum Kind { NONE = 0; }
                  Inner inner = 1;
                }
                """));

        assertEquals(List.of("proto:in.wynk.Outer", "proto:in.wynk.Outer.Inner", "proto:in.wynk.Outer.Kind"),
                keys(proto.extractExports(repository)));
    }

    @Test
    void syntheticMapEntryMessagesAreNotContracts() throws IOException {
        var repository = repo(Map.of("a.proto", """
                syntax = "proto3";
                package in.wynk;
                message WithMap { map<string, string> labels = 1; }
                """));

        assertEquals(List.of("proto:in.wynk.WithMap"), keys(proto.extractExports(repository)));
    }

    @Test
    void unpackagedProtoStillProducesIdentity() throws IOException {
        var repository = repo(Map.of("a.proto", """
                syntax = "proto3";
                message Loose { string v = 1; }
                """));

        assertEquals(List.of("proto:Loose"), keys(proto.extractExports(repository)));
    }

    @Test
    void wellKnownTypesAreNotTreatedAsImports() throws IOException {
        var repository = repo(Map.of("a.proto", """
                syntax = "proto3";
                package in.wynk;
                import "google/protobuf/timestamp.proto";
                message Evt { google.protobuf.Timestamp at = 1; }
                """));

        assertTrue(proto.extractImports(repository).isEmpty(),
                "protoc ships the well-known types; they are not a cross-repository contract");
    }

    @Test
    void inRepoReferencesAreNotImports() throws IOException {
        var repository = repo(Map.of("src/main/proto/Campaign.proto", ProtoExtractorTestSupport.CAMPAIGN_PROTO));

        List<ContractImport> imports = proto.extractImports(repository);

        assertTrue(imports.isEmpty(), () -> "everything referenced is declared in-repo, got " + imports);
    }

    @Test
    void extractsPythonGeneratedProtobufImportsWithoutProtoSources() throws IOException {
        var repository = repo(Map.of("client.py", "from catalog_pb2 import Item, Category\n"));

        assertEquals(List.of("proto:catalog.Category", "proto:catalog.Item"),
                proto.extractImports(repository).stream().map(ContractImport::canonicalKey).sorted().toList());
    }

    @Test
    void extractionIsDeterministicAcrossRuns() throws IOException {
        var repository = repo(Map.of("src/main/proto/Campaign.proto", ProtoExtractorTestSupport.CAMPAIGN_PROTO));

        loader.clearCache();
        List<ContractExport> first = proto.extractExports(repository);
        loader.clearCache();
        List<ContractExport> second = proto.extractExports(repository);

        assertEquals(keys(first), keys(second));
        assertEquals(
                first.stream().map(ContractExport::contentHash).toList(),
                second.stream().map(ContractExport::contentHash).toList());
    }

    @Test
    void commentsAndFormattingDoNotChangeContentHash() throws IOException {
        var plain = repo(Map.of("a.proto", """
                syntax = "proto3";
                package in.wynk;
                message Campaign { string id = 1; string name = 2; }
                """));
        String plainHash = proto.extractExports(plain).get(0).contentHash();

        loader.clearCache();
        Path other = tempDir.resolve("other");
        var commented = ProtoExtractorTestSupport.repo(other, Map.of("a.proto", """
                syntax = "proto3";
                package in.wynk;

                // A campaign. This comment is new.
                message Campaign {
                  string id   = 1;   // identifier
                  string name = 2;
                }
                """));

        assertEquals(plainHash, proto.extractExports(commented).get(0).contentHash());
    }

    @Test
    void fieldRenumberingChangesContentHash() throws IOException {
        var before = repo(Map.of("a.proto", """
                syntax = "proto3";
                package in.wynk;
                message Campaign { string id = 1; string name = 2; }
                """));
        String beforeHash = proto.extractExports(before).get(0).contentHash();

        loader.clearCache();
        var after = ProtoExtractorTestSupport.repo(tempDir.resolve("after"), Map.of("a.proto", """
                syntax = "proto3";
                package in.wynk;
                message Campaign { string id = 1; string name = 4; }
                """));

        assertNotEquals(beforeHash, proto.extractExports(after).get(0).contentHash(),
                "renumbering a field is a wire-breaking change and must move the hash");
    }

    @Test
    void malformedProtoDegradesToEmptyRatherThanThrowing() throws IOException {
        var repository = repo(Map.of("broken.proto", "this is not valid proto at all {{{"));

        assertTrue(proto.extractExports(repository).isEmpty());
        assertFalse(loader.load(repository).failures().isEmpty(), "the failure is recorded, not swallowed silently");
    }

    @Test
    void oneValidAndOneBrokenProtoStillYieldsTheValidContracts() throws IOException {
        var repository = repo(Map.of(
                "good/a.proto", """
                        syntax = "proto3";
                        package in.wynk.good;
                        message Fine { string v = 1; }
                        """,
                "bad/b.proto", "not proto {{{"));

        assertEquals(List.of("proto:in.wynk.good.Fine"), keys(proto.extractExports(repository)));
    }

    @Test
    void repositoryWithoutProtosYieldsNoContracts() throws IOException {
        var repository = repo(Map.of("README.md", "# no protos here"));

        assertTrue(proto.extractExports(repository).isEmpty());
        assertTrue(grpc.extractExports(repository).isEmpty());
        assertTrue(loader.load(repository).failures().isEmpty());
    }

    @Test
    void buildOutputDirectoriesAreIgnored() throws IOException {
        var repository = repo(Map.of(
                "src/main/proto/Real.proto", """
                        syntax = "proto3";
                        package in.wynk;
                        message Real { string v = 1; }
                        """,
                "target/generated/Copy.proto", """
                        syntax = "proto3";
                        package in.wynk.generated;
                        message Copy { string v = 1; }
                        """));

        assertEquals(List.of("proto:in.wynk.Real"), keys(proto.extractExports(repository)));
    }

    @Test
    void extractorsReportStableIdentityAndVersion() {
        assertEquals("proto", proto.id());
        assertEquals("grpc", grpc.id());
        assertEquals(ContractType.PROTO, proto.type());
        assertEquals(ContractType.GRPC, grpc.type());
        assertEquals(ProtoContractExtractor.VERSION, proto.version());
    }

    static final class ProtoExtractorTestSupport {
        private ProtoExtractorTestSupport() {}
        static boolean protocAvailable() { return com.wynk.codecontext.port.git.ProcessRunner.commandExists("protoc"); }
        static RepositoryContext repo(Path root, Map<String,String> files) throws IOException {
            for (var entry : files.entrySet()) {
                Path target = root.resolve(entry.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, entry.getValue());
            }
            return new RepositoryContext("WynkLimited", "test-repo", "0".repeat(40), "preprod", root);
        }
        static final String CAMPAIGN_PROTO = """
                syntax = "proto3";
                package in.wynk.userGrowth;
                message Campaign { string id = 1; string name = 2; Realm realm = 3; }
                enum Realm { UNKNOWN = 0; MUSIC = 1; }
                service CampaignService { rpc GetCampaign(GetCampaignRequest) returns (GetCampaignResponse); }
                message GetCampaignRequest { string campaignId = 1; }
                message GetCampaignResponse { Campaign campaign = 1; }
                """;
    }
}
