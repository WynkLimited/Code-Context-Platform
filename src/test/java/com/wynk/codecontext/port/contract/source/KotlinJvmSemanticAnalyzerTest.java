package com.wynk.codecontext.port.contract.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KotlinJvmSemanticAnalyzerTest {
    @TempDir Path root;
    private KotlinJvmSemanticAnalyzer analyzer(){return new KotlinJvmSemanticAnalyzer(System.getenv().getOrDefault("KOTLIN_COMPILER",Path.of("target/kotlinc/bin/kotlinc").toAbsolutePath().toString()));}
    @Test void compilesMixedLanguageOverloadsAccessorsExtensionsAndJavaCalls() throws Exception {
        Path kotlin=root.resolve("Money.kt"), java=root.resolve("JavaApi.java"), consumer=root.resolve("Consumer.java");
        Files.writeString(java,"package fixture; public class JavaApi { public static String convert(String s) { return s; } }");
        Files.writeString(kotlin,"""
                package fixture
                interface Converter { fun convert(s: String): String }
                class Money(val label: String): Converter {
                    override fun convert(s: String): String = JavaApi.convert(s)
                    fun convert(n: Int): String = n.toString()
                }
                fun String.decorate(): String = this + "!"
                fun entry(): String = Money("label").convert("x").decorate()
                """);
        Files.writeString(consumer,"package fixture; class Consumer { String call(Money m) { return m.convert(\"x\") + m.getLabel() + MoneyKt.entry(); } }");
        var result=analyzer().analyze(root,List.of(kotlin,java,consumer),List.of(),root.resolve("out"));
        assertTrue(result.complete(),result.diagnostic());
        Set<String> declarations=new HashSet<>();
        result.declarations().forEach(d->declarations.add(d.symbol().declaringType()+"#"+d.symbol().signature()));
        assertTrue(declarations.containsAll(Set.of("fixture.Money#convert(java.lang.String)","fixture.Money#convert(int)","fixture.Money#getLabel()","fixture.Money#Money(java.lang.String)","fixture.MoneyKt#entry()","fixture.MoneyKt#decorate(java.lang.String)","fixture.Converter#convert(java.lang.String)")),declarations.toString());
        assertTrue(result.calls().stream().anyMatch(c->c.call().declaringType().equals("fixture.JavaApi")&&c.call().signature().equals("convert(java.lang.String)")&&c.call().startLine()==4));
        var javaCalls=JavaSemanticCallSiteAnalyzer.analyze(consumer,List.of(root),List.of(result.jar()));
        assertTrue(javaCalls.parseComplete());
        assertTrue(javaCalls.callSites().stream().anyMatch(c->"fixture.Money".equals(c.declaringType())&&"convert(java.lang.String)".equals(c.signature())));
        assertTrue(javaCalls.callSites().stream().anyMatch(c->"fixture.MoneyKt".equals(c.declaringType())&&"entry()".equals(c.signature())));
    }
    @Test void removedOverloadAndUnresolvableDependencyDoNotProduceInventedCalls() throws Exception {
        Path source=root.resolve("Provider.kt");
        Files.writeString(source,"package fixture\nclass Provider { fun convert(s: String)=s; fun convert(n: Int)=n }");
        var before=analyzer().analyze(root,List.of(source),List.of(),root.resolve("before"));
        assertTrue(before.complete(),before.diagnostic());
        Files.writeString(source,"package fixture\nclass Provider { fun convert(n: Int)=n }");
        var after=analyzer().analyze(root,List.of(source),List.of(),root.resolve("after"));
        assertTrue(after.complete(),after.diagnostic());
        assertTrue(before.declarations().stream().anyMatch(d->d.symbol().signature().equals("convert(java.lang.String)")));
        assertFalse(after.declarations().stream().anyMatch(d->d.symbol().signature().equals("convert(java.lang.String)")));
        assertTrue(after.declarations().stream().anyMatch(d->d.symbol().signature().equals("convert(int)")));
        Files.writeString(source,"package fixture\nfun call() = missing.Dependency.convert(\"x\")");
        var missing=analyzer().analyze(root,List.of(source),List.of(),root.resolve("missing"));
        assertFalse(missing.complete());assertTrue(missing.calls().isEmpty());
        assertTrue(missing.diagnostic().startsWith("KOTLIN_COMPILATION_FAILED"),missing.diagnostic());
    }
    @Test void dependencyVersionControlsResolvedOverloads() throws Exception {
        Path provider=root.resolve("Library.kt");
        Files.writeString(provider,"package lib\nclass Library { fun convert(s: String)=s; fun convert(n: Int)=n }");
        var v1=analyzer().analyze(root,List.of(provider),List.of(),root.resolve("v1"));
        assertTrue(v1.complete(),v1.diagnostic());
        Files.writeString(provider,"package lib\nclass Library { fun convert(s: Long)=s; fun convert(n: Int)=n }");
        var v2=analyzer().analyze(root,List.of(provider),List.of(),root.resolve("v2"));
        assertTrue(v2.complete(),v2.diagnostic());
        Path consumer=root.resolve("Caller.kt");
        Files.writeString(consumer,"package app\nfun call()=lib.Library().convert(\"x\")");
        var pinned=analyzer().analyze(root,List.of(consumer),List.of(v1.jar()),root.resolve("pinned"));
        assertTrue(pinned.complete(),pinned.diagnostic());
        assertTrue(pinned.calls().stream().anyMatch(c->c.call().declaringType().equals("lib.Library")&&c.call().signature().equals("convert(java.lang.String)")));
        var upgraded=analyzer().analyze(root,List.of(consumer),List.of(v2.jar()),root.resolve("upgraded"));
        assertFalse(upgraded.complete());assertTrue(upgraded.calls().isEmpty());
        Files.writeString(consumer,"package app\nfun call()=lib.Library().convert(1)");
        var unaffected=analyzer().analyze(root,List.of(consumer),List.of(v2.jar()),root.resolve("unaffected"));
        assertTrue(unaffected.complete(),unaffected.diagnostic());
        assertTrue(unaffected.calls().stream().anyMatch(c->c.call().signature().equals("convert(int)")));
    }


    @Test void rejectsSymlinkEscapeAndExternalOutput() throws Exception {
        Path outside=Files.createTempFile("outside-kotlin-", ".kt");
        try {
            Files.writeString(outside,"fun leaked()=1");
            Path link=root.resolve("linked");
            Files.createSymbolicLink(link,outside.getParent());
            assertThrows(IllegalArgumentException.class,()->analyzer().analyze(root,List.of(link.resolve(outside.getFileName())),List.of(),root.resolve("out")));
            Path safe=root.resolve("Safe.kt");Files.writeString(safe,"fun safe()=1");
            assertThrows(IllegalArgumentException.class,()->analyzer().analyze(root,List.of(safe),List.of(),outside.getParent()));
        } finally { Files.deleteIfExists(outside); }
    }
    @Test void removesSecretsAndJvmInjectionEnvironment() {
        var environment=new HashMap<>(Map.of("PATH","/usr/bin","JAVA_HOME","/jdk",
                "DATABASE_PASSWORD","secret","JAVA_TOOL_OPTIONS","-javaagent:evil.jar",
                "JDK_JAVA_OPTIONS","@evil-options","KOTLIN_OPTS","-Xplugin=evil.jar","KOTLIN_COMPILER","/evil/compiler",
                "BASH_ENV","/evil","GIT_ACCESS_TOKEN","token"));
        KotlinJvmSemanticAnalyzer.configureEnvironment(environment);
        assertEquals(Set.of("PATH","JAVA_HOME","JAVA_OPTS","LANG"),environment.keySet());
        assertEquals("-Xmx512m -Dfile.encoding=UTF-8",environment.get("JAVA_OPTS"));
    }
}
