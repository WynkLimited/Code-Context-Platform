package com.wynk.codecontext.port.contract;

import com.wynk.codecontext.port.contract.ContractModels.ContractIdentity;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractIdentityTest {

    @Test
    void protoIdentityUsesFullyQualifiedName() {
        var identity = ContractIdentity.ofProto("in.wynk.userGrowth", "CampaignResponse");

        assertEquals("proto:in.wynk.userGrowth.CampaignResponse", identity.canonicalKey());
        assertEquals(ContractType.PROTO, identity.type());
        assertEquals("in.wynk.userGrowth", identity.namespace());
        assertEquals("CampaignResponse", identity.name());
        assertEquals("in.wynk.userGrowth.CampaignResponse", identity.qualifiedName());
    }

    @Test
    void grpcIdentityIncludesServiceAndMethod() {
        var identity = ContractIdentity.ofGrpc("in.wynk.userGrowth", "CampaignService", "GetCampaign");

        assertEquals("grpc:in.wynk.userGrowth.CampaignService/GetCampaign", identity.canonicalKey());
        assertEquals("GetCampaign", identity.operation());
    }

    @Test
    void sameContractProducesSameIdentity() {
        assertEquals(
                ContractIdentity.ofProto("in.wynk.userGrowth", "Campaign"),
                ContractIdentity.ofProto("in.wynk.userGrowth", "Campaign"));
        assertEquals(
                ContractIdentity.ofGrpc("a.b", "S", "M").canonicalKey(),
                ContractIdentity.ofGrpc("a.b", "S", "M").canonicalKey());
    }

    @Test
    void whitespaceDoesNotChangeIdentity() {
        assertEquals(
                ContractIdentity.ofProto("in.wynk.userGrowth", "Campaign").canonicalKey(),
                ContractIdentity.ofProto("  in.wynk.userGrowth  ", "  Campaign  ").canonicalKey());
    }

    @Test
    void differentContractsProduceDifferentIdentities() {
        var a = ContractIdentity.ofProto("in.wynk.userGrowth", "Campaign");
        var b = ContractIdentity.ofProto("in.wynk.catalog", "Campaign");
        var c = ContractIdentity.ofProto("in.wynk.userGrowth", "CampaignRequest");

        assertNotEquals(a.canonicalKey(), b.canonicalKey(), "package is part of identity");
        assertNotEquals(a.canonicalKey(), c.canonicalKey(), "message name is part of identity");
    }

    @Test
    void sameNameUnderDifferentTypesDoesNotCollide() {
        var proto = ContractIdentity.ofProto("in.wynk", "CampaignService");
        var grpc = ContractIdentity.ofGrpc("in.wynk", "CampaignService", "Get");

        assertNotEquals(proto.canonicalKey(), grpc.canonicalKey());
        assertTrue(proto.canonicalKey().startsWith("proto:"));
        assertTrue(grpc.canonicalKey().startsWith("grpc:"));
    }

    @Test
    void grpcMethodsOnOneServiceAreDistinctContracts() {
        assertNotEquals(
                ContractIdentity.ofGrpc("in.wynk", "CampaignService", "GetCampaign").canonicalKey(),
                ContractIdentity.ofGrpc("in.wynk", "CampaignService", "ListCampaigns").canonicalKey());
    }

    @Test
    void unqualifiedProtoMessageOmitsThePackageSegment() {
        var identity = ContractIdentity.ofProto(null, "Campaign");

        assertEquals("proto:Campaign", identity.canonicalKey());
        assertNull(identity.namespace());
        assertEquals("Campaign", identity.qualifiedName());
    }

    @Test
    void mavenIdentityExcludesVersionSoSkewedConsumersStillMatch() {
        var producer = ContractIdentity.ofMaven("in.wynk", "campaign-grpc-common", "2.4.0");
        var consumer = ContractIdentity.ofMaven("in.wynk", "campaign-grpc-common", "2.3.0");

        assertEquals(producer.canonicalKey(), consumer.canonicalKey(),
                "version skew must still produce an edge — it is the thing we are looking for");
        assertEquals("maven:in.wynk:campaign-grpc-common", producer.canonicalKey());
        assertEquals("2.4.0", producer.version());
        assertEquals("2.3.0", consumer.version());
        assertFalse(ContractType.MAVEN.versionInIdentity());
    }

    @Test
    void restIdentityNormalizesMethodCaseAndPath() {
        assertEquals("rest:GET:/campaign/{id}",
                ContractIdentity.ofRest("get", "campaign/{id}/").canonicalKey());
        assertEquals("rest:POST:/campaign",
                ContractIdentity.ofRest("PoSt", "/campaign").canonicalKey());
        assertEquals("rest:GET:/", ContractIdentity.ofRest("GET", "  ").canonicalKey());
    }

    @Test
    void kafkaIdentityUsesTopicName() {
        assertEquals("kafka:campaign-events", ContractIdentity.ofKafka("campaign-events").canonicalKey());
    }

    @Test
    void advisoryTechnologyIdentitiesAreCanonicalAndTyped() {
        assertEquals("enum:in.wynk.Realm", ContractIdentity.ofEnum("in.wynk", "Realm").canonicalKey());
        assertEquals("config:feature.campaign.enabled",
                ContractIdentity.ofConfig("feature.campaign.enabled").canonicalKey());
        assertEquals("cache:campaign-by-id", ContractIdentity.ofCache("campaign-by-id").canonicalKey());
        assertEquals("database:campaigns", ContractIdentity.ofDatabase("campaigns").canonicalKey());
        assertEquals("string_format:wynkpremiere://listing/{title}/{collectionid}",
                ContractIdentity.ofStringFormat("WYNKPREMIERE://LISTING/{title}/{collectionId}").canonicalKey());
    }

    @Test
    void aliasesShareTheUnderlyingContractIdentity() {
        assertEquals(ContractIdentity.ofConfig("campaign.enabled"),
                ContractIdentity.ofFeatureFlag("campaign.enabled"));
        assertEquals(ContractIdentity.ofCache("campaign"),
                ContractIdentity.ofRedis("campaign"));
        assertEquals(ContractIdentity.ofStringFormat("wynk://campaign/{id}"),
                ContractIdentity.ofDeepLink("wynk://campaign/{id}"));
        assertEquals(ContractIdentity.ofStringFormat("https://api.wynk.in/campaign/{id}"),
                ContractIdentity.ofUriFormat("https://api.wynk.in/campaign/{id}"));
    }

    @Test
    void identityRequiresTypeAndCanonicalKey() {
        assertThrows(IllegalArgumentException.class,
                () -> new ContractIdentity(null, "ns", "n", null, null, "proto:ns.n"));
        assertThrows(IllegalArgumentException.class,
                () -> new ContractIdentity(ContractType.PROTO, "ns", "n", null, null, "  "));
        assertThrows(IllegalArgumentException.class,
                () -> ContractIdentity.ofProto("in.wynk", "  "));
    }

    @Test
    void contractTypeIdRoundTrips() {
        for (ContractType type : ContractType.values()) {
            assertEquals(Optional.of(type), ContractType.fromId(type.id()));
            assertEquals(Optional.of(type), ContractType.fromId(type.name()));
        }
        assertEquals("string_format", ContractType.STRING_FORMAT.id());
        assertTrue(ContractType.fromId("graphql").isEmpty());
        assertTrue(ContractType.fromId(null).isEmpty());
    }
}
