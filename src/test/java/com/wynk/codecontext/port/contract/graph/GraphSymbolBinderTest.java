package com.wynk.codecontext.port.contract.graph;

import com.wynk.codecontext.port.contract.ContractModels;
import com.wynk.codecontext.port.contract.ContractModels.ContractExport;
import com.wynk.codecontext.port.contract.ContractModels.ContractIndex;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphSymbolBinderTest {
    @Test void ambiguousOverloadsRemainExplicitlyUnresolved() {
        var binder=new GraphSymbolBinder((generation,file)->List.of(node(1,"METHOD","getCampaign",10,10),node(2,"METHOD","getCampaign",10,10)));
        var index=index(Map.of("sourceRange",Map.of("startLine",10)));
        var result=binder.bind(index,UUID.randomUUID());
        assertEquals("AMBIGUOUS",result.exports().get(0).metadata().get("graphBindingStatus"));
        assertEquals(2,((List<?>)result.exports().get(0).metadata().get("graphBindingCandidates")).size());
    }

    @Test void exactSourceSymbolResolvesWithGraphNodeEvidence() {
        var binder=new GraphSymbolBinder((generation,file)->List.of(node(7,"CLASS","com.acme.CampaignController",1,40)));
        var result=binder.bind(index(Map.of()),UUID.randomUUID());
        assertEquals("RESOLVED",result.exports().get(0).metadata().get("graphBindingStatus"));
        assertEquals(7L,result.exports().get(0).metadata().get("boundCodeNodeId"));
    }

    @Test void ambiguousBindingReevaluatesToResolvedWhenOverloadBecomesUnique() {
        var ambiguous=new GraphSymbolBinder((generation,file)->List.of(
                node(1,"METHOD","getCampaign",10,10), node(2,"METHOD","getCampaign",10,10)));
        var first=ambiguous.bind(index(Map.of("sourceRange",Map.of("startLine",10))),UUID.randomUUID());
        assertEquals("AMBIGUOUS",first.exports().get(0).metadata().get("graphBindingStatus"));

        var resolved=new GraphSymbolBinder((generation,file)->List.of(node(1,"METHOD","getCampaign",10,12)));
        var second=resolved.bind(index(Map.of("sourceRange",Map.of("startLine",10))),UUID.randomUUID());
        assertEquals("RESOLVED",second.exports().get(0).metadata().get("graphBindingStatus"));
        assertEquals(1L,second.exports().get(0).metadata().get("boundCodeNodeId"));
    }

    private static GraphSymbolBinder.NodeCandidate node(long id,String type,String symbol,int start,int end) {
        return new GraphSymbolBinder.NodeCandidate(id,type,symbol,symbol,start,end,Map.of("id","g"+id));
    }
    private static ContractIndex index(Map<String,Object> metadata) {
        var identity=ContractModels.ContractIdentity.ofRest("GET","/campaign/{id}");
        var export=new ContractExport(identity,ContractType.REST,"A.java","com.acme.CampaignController","abc",metadata);
        return new ContractIndex("acme","api","0123456789012345678901234567890123456789","main",ContractModels.INDEX_SCHEMA_VERSION,Map.of(),ContractModels.COMPATIBILITY_RULES_VERSION,ContractModels.GENERATOR_VERSION,Instant.now(),null,List.of(export),List.of());
    }
}
