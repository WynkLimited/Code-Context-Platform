package com.wynk.codecontext.port.contract.graph;

import com.wynk.codecontext.port.contract.ContractModels;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractImport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavenConsumerBridgeTest {
    @Test void routesConsumerOfProducerReactorArtifact() {
        var changed=ContractModels.ContractIdentity.ofMaven("com.acme","common-contracts","2.0");
        var facade=ContractModels.ContractIdentity.ofMaven("com.acme","service-api","1.0");
        var exports=List.of(new ContractExport(facade,ContractType.MAVEN,"service/pom.xml","service-api","hash",Map.of()));
        var imports=List.of(new ContractImport(changed,ContractType.MAVEN,"service/pom.xml","com.acme:common-contracts","2.0",Map.of()));
        var index=new ContractIndex("acme","provider","0123456789012345678901234567890123456789","main",ContractModels.INDEX_SCHEMA_VERSION,Map.of(),ContractModels.COMPATIBILITY_RULES_VERSION,ContractModels.GENERATOR_VERSION,Instant.now(),"hash",exports,imports);
        var change=new CompatibilityModels.ContractChange(changed,CompatibilityModels.ContractChangeType.BUILD_DEPENDENCY_CHANGED,CompatibilityModels.Compatibility.UNKNOWN,"old","new",null,null,ContractModels.COMPATIBILITY_RULES_VERSION,"version changed");
        assertEquals(List.of(changed.canonicalKey(),facade.canonicalKey()),MavenConsumerBridge.consumerMatchKeys(index,change));
    }

    @Test void matchesRestWildcardMethodByNormalizedPath() {
        var wildcard=ContractModels.ContractIdentity.ofRest("*","/campaign/{id}");
        var get=ContractModels.ContractIdentity.ofRest("GET","/campaign/{id}");
        var imp=new ContractImport(get,ContractType.REST,"src/Client.java",get.canonicalKey(),null,Map.of());
        var index=new ContractIndex("acme","consumer","0123456789012345678901234567890123456789","main",ContractModels.INDEX_SCHEMA_VERSION,Map.of(),ContractModels.COMPATIBILITY_RULES_VERSION,ContractModels.GENERATOR_VERSION,Instant.now(),"hash",List.of(),List.of(imp));
        assertEquals(List.of("src/Client.java"),MavenConsumerBridge.consumerImportFiles(index,List.of(wildcard.canonicalKey())));
    }
}
