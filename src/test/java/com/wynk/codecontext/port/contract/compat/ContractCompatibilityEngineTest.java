package com.wynk.codecontext.port.contract.compat;

import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.Compatibility;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChange;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChangeType;
import com.wynk.codecontext.port.model.ProductModels.FindingProvenance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractCompatibilityEngineTest {

    private final ContractCompatibilityEngine engine = new ContractCompatibilityEngine();

    private static ContractExport message(String name, String signatureBody) {
        String signature = "message in.wynk." + name + "{" + signatureBody + "}";
        return export(ContractIdentity.ofProto("in.wynk", name), ContractType.PROTO, signature);
    }

    private static ContractExport enumType(String name, String signatureBody) {
        String signature = "enum in.wynk." + name + "{" + signatureBody + "}";
        return export(ContractIdentity.ofProto("in.wynk", name), ContractType.PROTO, signature);
    }

    private static ContractExport rpc(String service, String method, String request, String response) {
        return rpc(service, method, request, response, false, false);
    }

    private static ContractExport rpc(
            String service, String method, String request, String response,
            boolean clientStreaming, boolean serverStreaming) {
        String signature = "rpc in.wynk." + service + "/" + method
                + "(in.wynk." + request + ") returns (in.wynk." + response + ")"
                + ":clientStreaming=" + clientStreaming + ":serverStreaming=" + serverStreaming;
        return export(ContractIdentity.ofGrpc("in.wynk", service, method), ContractType.GRPC, signature);
    }

    private static ContractExport export(ContractIdentity identity, ContractType type, String signature) {
        return new ContractExport(identity, type, "src/main/proto/a.proto", identity.qualifiedName(),
                "sha256:" + Integer.toHexString(signature.hashCode()),
                Map.of("signature", signature));
    }

    private ContractChange only(List<ContractExport> before, List<ContractExport> after) {
        List<ContractChange> changes = engine.compare(before, after);
        assertEquals(1, changes.size(), () -> "expected exactly one change, got " + describe(changes));
        return changes.get(0);
    }

    private static String describe(List<ContractChange> changes) {
        return changes.stream().map(c -> c.changeType() + "/" + c.compatibility() + ": " + c.detail()).toList().toString();
    }

    private static final ContractExport CAMPAIGN_V1 =
            message("Campaign", "1:id:TYPE_STRING:LABEL_OPTIONAL;2:name:TYPE_STRING:LABEL_OPTIONAL");

    @Test
    void identicalContractsProduceNoChanges() {
        assertTrue(engine.compare(List.of(CAMPAIGN_V1), List.of(CAMPAIGN_V1)).isEmpty());
    }

    @Test
    void fieldAddedIsBackwardCompatible() {
        var after = message("Campaign",
                "1:id:TYPE_STRING:LABEL_OPTIONAL;2:name:TYPE_STRING:LABEL_OPTIONAL;3:tag:TYPE_STRING:LABEL_OPTIONAL");

        ContractChange change = only(List.of(CAMPAIGN_V1), List.of(after));

        assertEquals(ContractChangeType.FIELD_ADDED, change.changeType());
        assertEquals(Compatibility.BACKWARD_COMPATIBLE, change.compatibility());
        assertFalse(change.isBreaking());
    }

    @Test
    void fieldRemovedIsBreaking() {
        var after = message("Campaign", "1:id:TYPE_STRING:LABEL_OPTIONAL");

        ContractChange change = only(List.of(CAMPAIGN_V1), List.of(after));

        assertEquals(ContractChangeType.FIELD_REMOVED, change.changeType());
        assertTrue(change.isBreaking());
        assertEquals(FindingProvenance.CONTRACT_DRIFT, change.provenance());
    }

    @Test
    void fieldNumberChangedIsBreakingAndNotReportedAsAddPlusRemove() {
        var after = message("Campaign",
                "1:id:TYPE_STRING:LABEL_OPTIONAL;4:name:TYPE_STRING:LABEL_OPTIONAL");

        ContractChange change = only(List.of(CAMPAIGN_V1), List.of(after));

        assertEquals(ContractChangeType.FIELD_NUMBER_CHANGED, change.changeType());
        assertTrue(change.isBreaking());
        assertTrue(change.detail().contains("from number 2 to 4"), change.detail());
    }

    @Test
    void fieldTypeChangedIsBreaking() {
        var after = message("Campaign",
                "1:id:TYPE_STRING:LABEL_OPTIONAL;2:name:TYPE_INT64:LABEL_OPTIONAL");

        ContractChange change = only(List.of(CAMPAIGN_V1), List.of(after));

        assertEquals(ContractChangeType.FIELD_TYPE_CHANGED, change.changeType());
        assertTrue(change.isBreaking());
    }

    @Test
    void wireCompatibleTypeWideningIsStillBreakingBecauseTheGeneratedApiChanges() {
        var before = message("M", "1:count:TYPE_INT32:LABEL_OPTIONAL");
        var after = message("M", "1:count:TYPE_INT64:LABEL_OPTIONAL");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.FIELD_TYPE_CHANGED, change.changeType());
        assertTrue(change.isBreaking(), "int32→int64 keeps the wire but changes the accessor signature");
    }

    @Test
    void fieldCardinalityChangedIsBreaking() {
        var before = message("M", "1:tags:TYPE_STRING:LABEL_OPTIONAL");
        var after = message("M", "1:tags:TYPE_STRING:LABEL_REPEATED");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.FIELD_CARDINALITY_CHANGED, change.changeType());
        assertTrue(change.isBreaking());
    }

    @Test
    void movingAFieldIntoAOneofIsBreaking() {
        var before = message("M", "1:a:TYPE_STRING:LABEL_OPTIONAL");
        var after = message("M", "1:a:TYPE_STRING:LABEL_OPTIONAL:oneof=choice");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.FIELD_CARDINALITY_CHANGED, change.changeType());
        assertTrue(change.detail().contains("oneof membership"), change.detail());
    }

    @Test
    void fieldRenameKeepingNumberAndTypeIsBreaking() {
        var before = message("M", "1:artworkUrl:TYPE_STRING:LABEL_OPTIONAL");
        var after = message("M", "1:artwork_url:TYPE_STRING:LABEL_OPTIONAL");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.FIELD_TYPE_CHANGED, change.changeType());
        assertTrue(change.detail().contains("renamed"), change.detail());
    }

    @Test
    void messageRenamedIsDetectedAsARenameNotAnUnrelatedAddAndRemove() {
        var before = message("Campaign", "1:id:TYPE_STRING:LABEL_OPTIONAL");
        var after = message("CampaignV2", "1:id:TYPE_STRING:LABEL_OPTIONAL");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.MESSAGE_RENAMED, change.changeType());
        assertTrue(change.isBreaking());
        assertTrue(change.detail().contains("renamed to"), change.detail());
    }

    @Test
    void packageChangeIsReportedAsPackageChanged() {
        var before = export(ContractIdentity.ofProto("in.wynk.old", "Campaign"), ContractType.PROTO,
                "message in.wynk.old.Campaign{1:id:TYPE_STRING:LABEL_OPTIONAL}");
        var after = export(ContractIdentity.ofProto("in.wynk.new", "Campaign"), ContractType.PROTO,
                "message in.wynk.new.Campaign{1:id:TYPE_STRING:LABEL_OPTIONAL}");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.PACKAGE_CHANGED, change.changeType());
        assertTrue(change.isBreaking());
    }

    @Test
    void enumValueAddedIsBackwardCompatible() {
        var before = enumType("Realm", "0:NONE;1:MUSIC");
        var after = enumType("Realm", "0:NONE;1:MUSIC;2:VIDEO");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.ENUM_VALUE_ADDED, change.changeType());
        assertEquals(Compatibility.BACKWARD_COMPATIBLE, change.compatibility());
    }

    @Test
    void enumValueRemovedIsBreaking() {
        var before = enumType("Realm", "0:NONE;1:MUSIC");
        var after = enumType("Realm", "0:NONE");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.ENUM_VALUE_REMOVED, change.changeType());
        assertTrue(change.isBreaking());
    }

    @Test
    void enumValueRenamedIsBreaking() {
        var before = enumType("Realm", "0:NONE;1:MUSIC");
        var after = enumType("Realm", "0:NONE;1:AUDIO");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.ENUM_VALUE_REMOVED, change.changeType());
        assertTrue(change.isBreaking());
    }

    @Test
    void rpcAddedIsBackwardCompatible() {
        var before = List.of(rpc("CampaignService", "Get", "Req", "Res"));
        var after = List.of(rpc("CampaignService", "Get", "Req", "Res"),
                rpc("CampaignService", "List", "ListReq", "ListRes"));

        ContractChange change = only(before, after);

        assertEquals(ContractChangeType.RPC_ADDED, change.changeType());
        assertEquals(Compatibility.BACKWARD_COMPATIBLE, change.compatibility());
    }

    @Test
    void rpcRemovedIsBreakingAndCountsAsASignatureBreak() {
        var before = List.of(rpc("CampaignService", "Get", "Req", "Res"),
                rpc("CampaignService", "List", "ListReq", "ListRes"));
        var after = List.of(rpc("CampaignService", "Get", "Req", "Res"));

        ContractChange change = only(before, after);

        assertEquals(ContractChangeType.RPC_REMOVED, change.changeType());
        assertTrue(change.isBreaking());
        assertEquals(FindingProvenance.SIGNATURE_BREAK, change.provenance());
    }

    @Test
    void rpcRequestAndResponseChangesAreBreaking() {
        var requestChange = only(
                List.of(rpc("S", "M", "ReqV1", "Res")),
                List.of(rpc("S", "M", "ReqV2", "Res")));
        assertEquals(ContractChangeType.RPC_REQUEST_CHANGED, requestChange.changeType());
        assertEquals(FindingProvenance.SIGNATURE_BREAK, requestChange.provenance());

        var responseChange = only(
                List.of(rpc("S", "M", "Req", "ResV1")),
                List.of(rpc("S", "M", "Req", "ResV2")));
        assertEquals(ContractChangeType.RPC_RESPONSE_CHANGED, responseChange.changeType());
        assertTrue(responseChange.isBreaking());
    }

    @Test
    void rpcStreamingCardinalityChangeIsBreaking() {
        ContractChange change = only(
                List.of(rpc("S", "M", "Req", "Res", false, false)),
                List.of(rpc("S", "M", "Req", "Res", false, true)));

        assertEquals(ContractChangeType.RPC_REQUEST_CHANGED, change.changeType());
        assertTrue(change.detail().contains("streaming"), change.detail());
    }

    @Test
    void serviceRenameIsDetectedAcrossTheRpcSet() {
        ContractChange change = only(
                List.of(rpc("CampaignService", "Get", "Req", "Res")),
                List.of(rpc("CampaignServiceV2", "Get", "Req", "Res")));

        assertEquals(ContractChangeType.SERVICE_RENAMED, change.changeType());
        assertEquals(FindingProvenance.SIGNATURE_BREAK, change.provenance());
    }

    @Test
    void newContractIsNotABreak() {
        ContractChange change = only(List.of(), List.of(CAMPAIGN_V1));

        assertEquals(ContractChangeType.CONTRACT_ADDED, change.changeType());
        assertFalse(change.isBreaking());
        assertEquals(FindingProvenance.INFERRED, change.provenance(),
                "a non-breaking change carries no deterministic provenance");
    }

    @Test
    void deletedContractIsABreak() {
        ContractChange change = only(List.of(CAMPAIGN_V1), List.of());

        assertEquals(ContractChangeType.CONTRACT_REMOVED, change.changeType());
        assertTrue(change.isBreaking());
    }

    @Test
    void multipleChangesInOneContractAreAllReported() {
        var after = message("Campaign",
                "1:id:TYPE_INT64:LABEL_OPTIONAL;3:tag:TYPE_STRING:LABEL_OPTIONAL");

        List<ContractChange> changes = engine.compare(List.of(CAMPAIGN_V1), List.of(after));

        var types = changes.stream().map(ContractChange::changeType).toList();
        assertTrue(types.contains(ContractChangeType.FIELD_TYPE_CHANGED), () -> describe(changes));
        assertTrue(types.contains(ContractChangeType.FIELD_REMOVED), () -> describe(changes));
        assertTrue(types.contains(ContractChangeType.FIELD_ADDED), () -> describe(changes));
    }

    @Test
    void everyChangeCarriesEvidenceAndRulesVersion() {
        ContractChange change = only(List.of(CAMPAIGN_V1),
                List.of(message("Campaign", "1:id:TYPE_STRING:LABEL_OPTIONAL")));

        assertEquals(engine.rulesVersion(), change.rulesVersion());
        assertTrue(change.beforeSignature().contains("name"), change.beforeSignature());
        assertFalse(change.afterSignature().contains("name"), change.afterSignature());
        assertFalse(change.detail().isBlank());
    }

    @Test
    void breakingContractsAreDeduplicatedForRouting() {
        var after = message("Campaign", "1:id:TYPE_INT64:LABEL_REPEATED");

        List<ContractChange> changes = engine.compare(List.of(CAMPAIGN_V1), List.of(after));
        var breaking = ContractCompatibilityEngine.breakingContracts(changes);

        assertTrue(changes.size() > 1, () -> describe(changes));
        assertEquals(1, breaking.size(), "one contract, however many individual breaks");
        assertEquals("proto:in.wynk.Campaign", breaking.get(0).canonicalKey());
    }

    @Test
    void identicalVendoredCopiesDoNotProduceSpuriousChanges() {
        var copyA = new ContractExport(CAMPAIGN_V1.contract(), ContractType.PROTO,
                "moduleA/Campaign.proto", "in.wynk.Campaign", CAMPAIGN_V1.contentHash(),
                CAMPAIGN_V1.metadata());
        var copyB = new ContractExport(CAMPAIGN_V1.contract(), ContractType.PROTO,
                "moduleB/Campaign.proto", "in.wynk.Campaign", CAMPAIGN_V1.contentHash(),
                CAMPAIGN_V1.metadata());

        assertTrue(engine.compare(List.of(copyA, copyB), List.of(copyA, copyB)).isEmpty());
    }

    @Test
    void comparisonIsDeterministicRegardlessOfInputOrder() {
        var realm = enumType("Realm", "0:NONE");
        var afterCampaign = message("Campaign", "1:id:TYPE_STRING:LABEL_OPTIONAL");

        List<ContractChange> one = engine.compare(List.of(CAMPAIGN_V1, realm), List.of(afterCampaign, realm));
        List<ContractChange> two = engine.compare(List.of(realm, CAMPAIGN_V1), List.of(realm, afterCampaign));

        assertEquals(describe(one), describe(two));
    }

    @Test
    void contractWithoutASignatureDoesNotCrashTheEngine() {
        var noSignature = new ContractExport(ContractIdentity.ofProto("in.wynk", "X"),
                ContractType.PROTO, "a.proto", "in.wynk.X", "sha256:aaa", Map.of());
        var changed = new ContractExport(ContractIdentity.ofProto("in.wynk", "X"),
                ContractType.PROTO, "a.proto", "in.wynk.X", "sha256:bbb", Map.of());

        assertTrue(engine.compare(List.of(noSignature), List.of(changed)).isEmpty());
    }

    @Test
    void mavenPublicApiRemovalIsDeterministicallyBreaking() {
        var identity = ContractIdentity.ofMaven("in.wynk", "campaign-api", "2.4.0");
        var before = export(identity, ContractType.MAVEN, "public String get(String id);\npublic void delete(String id);");
        var after = export(identity, ContractType.MAVEN, "public String get(String id);");

        ContractChange change = only(List.of(before), List.of(after));

        assertEquals(ContractChangeType.API_SIGNATURE_CHANGED, change.changeType());
        assertEquals(Compatibility.BREAKING, change.compatibility());
        assertEquals(FindingProvenance.SIGNATURE_BREAK, change.provenance());
    }

    @Test
    void kafkaSchemaChangesRemainAdvisoryUntilSchemaCompatibilityIsProven() {
        ContractIdentity identity = ContractIdentity.ofKafka("campaign-events");
        var before = new ContractExport(identity, ContractType.KAFKA, "application.properties",
                "campaign-events", "one", Map.of("signature", "schema-v1", "schema", "schema-v1"));
        var after = new ContractExport(identity, ContractType.KAFKA, "application.properties",
                "campaign-events", "two", Map.of("signature", "schema-v2", "schema", "schema-v2"));

        assertEquals(Compatibility.UNKNOWN, only(List.of(before), List.of(after)).compatibility());

        var adHocBefore = export(identity, ContractType.KAFKA, "pojo-v1");
        var adHocAfter = export(identity, ContractType.KAFKA, "pojo-v2");
        assertEquals(Compatibility.UNKNOWN, only(List.of(adHocBefore), List.of(adHocAfter)).compatibility());
    }

    @Test
    void openApiSignatureChangeIsAdvisoryUntilSemanticCompatibilityIsProven() {
        ContractIdentity endpoint = ContractIdentity.ofRest("GET", "/campaign/{id}");
        var before = new ContractExport(endpoint, ContractType.REST, "openapi.yaml", "GET",
                "one", Map.of("signature", "response:CampaignV1", "framework", "openapi"));
        var after = new ContractExport(endpoint, ContractType.REST, "openapi.yaml", "GET",
                "two", Map.of("signature", "response:CampaignV2", "framework", "openapi"));
        assertEquals(Compatibility.UNKNOWN, only(List.of(before), List.of(after)).compatibility());

        ContractExport config = export(
                ContractIdentity.ofConfig("feature.campaign.enabled"), ContractType.CONFIG, "boolean");
        assertEquals(Compatibility.UNKNOWN, only(List.of(config), List.of()).compatibility());
    }

    @Test
    void structuredRestOptionalResponseFieldAdditionIsCompatible() {
        ContractIdentity endpoint = ContractIdentity.ofRest("GET", "/campaign/{id}");
        var before = new ContractExport(endpoint, ContractType.REST, "openapi.json", "GET", "one",
                Map.of("signature", "restOp{params=path:id:required:string;request=;response=id:string:required}",
                        "framework", "openapi"));
        var after = new ContractExport(endpoint, ContractType.REST, "openapi.json", "GET", "two",
                Map.of("signature", "restOp{params=path:id:required:string;request=;response=id:string:required,tag:string:optional}",
                        "framework", "openapi"));

        ContractChange change = only(List.of(before), List.of(after));
        assertEquals(ContractChangeType.ENDPOINT_SIGNATURE_CHANGED, change.changeType());
        assertEquals(Compatibility.BACKWARD_COMPATIBLE, change.compatibility());
        assertTrue(change.detail().contains("response field"), change.detail());
    }

    @Test
    void structuredRestRequiredRequestFieldAdditionIsBreaking() {
        ContractIdentity endpoint = ContractIdentity.ofRest("POST", "/campaign");
        var before = new ContractExport(endpoint, ContractType.REST, "openapi.json", "POST", "one",
                Map.of("signature", "restOp{params=;request=name:string:required;response=id:string:required}",
                        "framework", "openapi"));
        var after = new ContractExport(endpoint, ContractType.REST, "openapi.json", "POST", "two",
                Map.of("signature", "restOp{params=;request=name:string:required,owner:string:required;response=id:string:required}",
                        "framework", "openapi"));

        ContractChange change = only(List.of(before), List.of(after));
        assertEquals(Compatibility.BREAKING, change.compatibility());
        assertTrue(change.detail().contains("request field"), change.detail());
    }

    @Test
    void structuredRestResponseFieldRemovalIsBreakingWhileRequestRemovalIsNot() {
        ContractIdentity endpoint = ContractIdentity.ofRest("PUT", "/campaign/{id}");
        var responseRemoval = only(
                List.of(new ContractExport(endpoint, ContractType.REST, "openapi.json", "PUT", "one",
                        Map.of("signature", "restOp{params=;request=;response=id:string:required,name:string:optional}",
                                "framework", "openapi"))),
                List.of(new ContractExport(endpoint, ContractType.REST, "openapi.json", "PUT", "two",
                        Map.of("signature", "restOp{params=;request=;response=id:string:required}",
                                "framework", "openapi"))));
        assertEquals(Compatibility.BREAKING, responseRemoval.compatibility());

        var requestRemoval = only(
                List.of(new ContractExport(endpoint, ContractType.REST, "openapi.json", "PUT", "one",
                        Map.of("signature", "restOp{params=;request=name:string:optional;response=id:string:required}",
                                "framework", "openapi"))),
                List.of(new ContractExport(endpoint, ContractType.REST, "openapi.json", "PUT", "two",
                        Map.of("signature", "restOp{params=;request=;response=id:string:required}",
                                "framework", "openapi"))));
        assertEquals(Compatibility.BACKWARD_COMPATIBLE, requestRemoval.compatibility());
    }

    @Test
    void kafkaAvroOptionalFieldAdditionIsCompatibleWhileRemovalBreaksConsumers() {
        ContractIdentity topic = ContractIdentity.ofKafka("campaign-events");
        var before = new ContractExport(topic, ContractType.KAFKA, "application.properties", "campaign-events",
                "one", Map.of("signature", "fields{id:string:required}", "schema", "v1", "direction", "producer"));
        var afterAdd = new ContractExport(topic, ContractType.KAFKA, "application.properties", "campaign-events",
                "two", Map.of("signature", "fields{id:string:required,tag:string:optional}", "schema", "v2", "direction", "producer"));
        assertEquals(Compatibility.BACKWARD_COMPATIBLE, only(List.of(before), List.of(afterAdd)).compatibility());

        var afterRemove = new ContractExport(topic, ContractType.KAFKA, "application.properties", "campaign-events",
                "three", Map.of("signature", "fields{}", "schema", "v3", "direction", "producer"));
        assertEquals(Compatibility.BREAKING, only(List.of(before), List.of(afterRemove)).compatibility());
    }

    @Test
    void kafkaProtobufFieldNumberChangeRemainsBreaking() {
        ContractIdentity topic = ContractIdentity.ofKafka("campaign-events");
        var before = new ContractExport(topic, ContractType.KAFKA, "schemas/campaign.proto", "campaign-events",
                "one", Map.of("signature", "message in.wynk.Campaign{1:id:TYPE_STRING:LABEL_OPTIONAL}",
                        "schema", "v1", "direction", "producer"));
        var after = new ContractExport(topic, ContractType.KAFKA, "schemas/campaign.proto", "campaign-events",
                "two", Map.of("signature", "message in.wynk.Campaign{2:id:TYPE_STRING:LABEL_OPTIONAL}",
                        "schema", "v2", "direction", "producer"));
        ContractChange change = only(List.of(before), List.of(after));
        assertEquals(ContractChangeType.FIELD_NUMBER_CHANGED, change.changeType());
        assertTrue(change.isBreaking());
    }
}
