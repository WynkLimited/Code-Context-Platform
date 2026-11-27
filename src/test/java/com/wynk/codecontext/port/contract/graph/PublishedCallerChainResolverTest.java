package com.wynk.codecontext.port.contract.graph;

import org.junit.jupiter.api.Test;
import java.util.*;
import static com.wynk.codecontext.port.contract.graph.CrossRepoImpactResolver.SemanticMethodImpact;
import static org.junit.jupiter.api.Assertions.*;

class PublishedCallerChainResolverTest {
    private final UUID generation=UUID.randomUUID();
    private final Map<String,UUID> snapshots=Map.of("fixture/consumer@"+"b".repeat(40),generation);
    private final String direct="app.Consumer#call(org.acme.Money)";
    @Test void returnsOrderedSnapshotEvidenceWithoutClaimingCurrentBreakage() {
        var resolver=new PublishedCallerChainResolver((team,root,gen,target,limit)->{
            assertEquals("team",team);assertEquals(generation,gen);
            return target.equals(direct)?List.of(edge("app.Entry#load()")):
                    target.equals("app.Entry#load()")?List.of(edge("app.Api#request()")):List.of();
        });
        var result=resolver.resolve("team",List.of(seed()),snapshots);
        assertEquals(2,result.chains().size());
        var chain=result.chains().get(1);
        assertEquals(List.of("app.Api#request()","app.Entry#load()",direct),chain.symbols());
        assertEquals(2,chain.depth());assertEquals(generation,chain.generationId());
        assertEquals("org.acme.Money#convert(java.lang.String)",chain.providerSymbol());
        assertEquals("org.acme:common-library:1.0",chain.dependencyArtifact());
        assertEquals("CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN",chain.currentCompatibility());
        assertFalse(chain.confirmedBreakage());assertFalse(result.complete());
        assertEquals("POTENTIAL_UPGRADE_CALLER",chain.classification());
        assertEquals(0.6,chain.confidence());
    }
    @Test void cyclesTerminateAndGenerationMismatchIsRejected() {
        var cycle=new PublishedCallerChainResolver((t,r,g,target,n)->List.of(edge(target.equals(direct)?"app.Entry#load()":direct)));
        assertEquals(1,cycle.resolve("team",List.of(seed()),snapshots).chains().size());
        var mixed=new PublishedCallerChainResolver((t,r,g,target,n)->List.of(new PublishedCallerChainResolver.Edge(UUID.randomUUID(),"app.Other#call()","Other.java",1,1,"different snapshot",0.9)));
        assertThrows(IllegalStateException.class,()->mixed.resolve("team",List.of(seed()),snapshots));
    }
    @Test void resultAndDepthBudgetsAreExplicit() {
        var wide=new PublishedCallerChainResolver((t,r,g,target,n)->java.util.stream.IntStream.range(0,n)
                .mapToObj(i->edge("app.Entry#call"+i+"()")).toList());
        var bounded=wide.resolve("team",List.of(seed()),snapshots);
        assertEquals(100,bounded.chains().size());assertTrue(bounded.truncated());
        var deep=new PublishedCallerChainResolver((t,r,g,target,n)->List.of(edge(target+"Next")));
        var depth=deep.resolve("team",List.of(seed()),snapshots);
        assertEquals(3,depth.chains().size());assertTrue(depth.depthBoundReached());
    }
    @Test void authenticationAndKnownSnapshotAreRequiredBeforeReadingEdges() {
        var resolver=new PublishedCallerChainResolver((t,r,g,target,n)->{fail("must not read edges without a captured snapshot");return List.of();});
        assertThrows(SecurityException.class,()->resolver.resolve("",List.of(seed()),snapshots));
        assertTrue(resolver.resolve("team",List.of(seed()),Map.of()).chains().isEmpty());
    }
    @Test void resolvesTheAcceptanceFixturesLocalCallerSignatures(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var app=java.nio.file.Files.createDirectories(root.resolve("app"));
        var acme=java.nio.file.Files.createDirectories(root.resolve("org/acme"));
        java.nio.file.Files.writeString(acme.resolve("Money.java"), "package org.acme; public class Money {}");
        java.nio.file.Files.writeString(app.resolve("ConsumerA.java"),
                "package app; public class ConsumerA { public String call(org.acme.Money money) { return null; } }");
        var entry=app.resolve("EntryPoint.java");
        java.nio.file.Files.writeString(entry, """
                package app;
                public class EntryPoint {
                  public String load(ConsumerA consumer, org.acme.Money money) { return consumer.call(money); }
                  public String request(ConsumerA consumer, org.acme.Money money) { return load(consumer,money); }
                }
                """);
        var calls=com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.analyze(entry,List.of(root),List.of()).callSites();
        assertEquals(2,calls.size());
        assertTrue(calls.stream().allMatch(call->"RESOLVED".equals(call.resolutionStatus())));
        assertEquals(Set.of("load(app.ConsumerA, org.acme.Money)","request(app.ConsumerA, org.acme.Money)"),
                calls.stream().map(com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.CallSite::callerMethod).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("call(org.acme.Money)","load(app.ConsumerA, org.acme.Money)"),
                calls.stream().map(com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.CallSite::signature).collect(java.util.stream.Collectors.toSet()));
    }
    private PublishedCallerChainResolver.Edge edge(String caller){return new PublishedCallerChainResolver.Edge(generation,caller,"Entry.java",10,10,"JAVAPARSER_SYMBOL_SOLVER",0.6);}
    private SemanticMethodImpact seed(){return new SemanticMethodImpact("fixture/provider","a".repeat(40),"fixture/consumer","b".repeat(40),"Consumer.java","app.Consumer","call(org.acme.Money)",5,5,"money.convert(value)","org.acme.Money","convert(java.lang.String)","before","after","org.acme:common-library:1.0","1.0",0.85,"resolved dependency call","POTENTIAL_UPGRADE_IMPACT","CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN","DIRECT","RESOLVED","CURRENT","PUBLISHED_V5_EDGE","MAVEN_RESOLVER",false);}
}
