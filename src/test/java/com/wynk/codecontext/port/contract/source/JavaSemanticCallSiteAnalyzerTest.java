package com.wynk.codecontext.port.contract.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class JavaSemanticCallSiteAnalyzerTest {
    @TempDir Path temp;

    @Test void resolvesOverloadAgainstActualSourceTypesAndPreservesUnresolvedCalls() throws Exception {
        Path root = temp.resolve("src/main/java");
        Path api = root.resolve("sample/Money.java"); Files.createDirectories(api.getParent());
        Files.writeString(api, "package sample; public class Money { public String convert(int x) { return \"i\"; } public String convert(String x) { return x; } }");
        Path consumer = root.resolve("sample/Payment.java");
        Files.writeString(consumer, "package sample; class Payment { String run(Money m) { return m.convert(1) + m.convert(\"x\") + missing(1); } }");
        var result = JavaSemanticCallSiteAnalyzer.analyze(consumer, List.of(root), List.of());
        assertTrue(result.parseComplete());
        var resolved = result.callSites().stream().filter(c -> c.expression().startsWith("m.convert")).toList();
        assertEquals(2, resolved.size());
        assertTrue(resolved.stream().allMatch(c -> c.resolutionStatus().equals("RESOLVED")));
        assertEquals(2, resolved.stream().map(JavaSemanticCallSiteAnalyzer.CallSite::signature).distinct().count());
        assertEquals(1, result.callSites().stream().filter(c -> c.expression().startsWith("missing")).count());
        assertEquals("UNRESOLVED", result.callSites().stream().filter(c -> c.expression().startsWith("missing")).findFirst().orElseThrow().resolutionStatus());
    }

    @Test void resolvesInheritedAndInterfaceDefaultMethods() throws Exception {
        Path root = temp.resolve("src/main/java");
        Path pkg = root.resolve("sample"); Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Base.java"), "package sample; class Base { public Number value() { return 1; } }");
        Files.writeString(pkg.resolve("HasName.java"), "package sample; interface HasName { default String name() { return \"n\"; } }");
        Files.writeString(pkg.resolve("Child.java"), "package sample; class Child extends Base implements HasName {}");
        Path caller = pkg.resolve("Caller.java"); Files.writeString(caller, "package sample; class Caller { Object run(Child c) { return c.value().toString() + c.name(); } }");
        var result = JavaSemanticCallSiteAnalyzer.analyze(caller, List.of(root), List.of());
        assertTrue(result.parseComplete());
        assertTrue(result.callSites().stream().filter(c -> c.expression().contains("value")).anyMatch(c -> "sample.Base".equals(c.declaringType())));
        assertTrue(result.callSites().stream().filter(c -> c.expression().contains("name")).anyMatch(c -> "sample.HasName".equals(c.declaringType())));
    }

    @Test void resolvesGenericBoundsVarargsAndPrimitiveWidening() throws Exception {
        Path root = temp.resolve("src/main/java"); Path pkg = root.resolve("sample"); Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Ops.java"), "package sample; class Ops { static <T extends Number> T first(T value) { return value; } static String join(String... values) { return String.join(\"\", values); } static long widen(long value) { return value; } }");
        Path caller = pkg.resolve("Caller.java"); Files.writeString(caller, "package sample; class Caller { Object run() { Integer n = Ops.first(1); return Ops.join(\"a\", \"b\") + Ops.widen(2); } }");
        var result = JavaSemanticCallSiteAnalyzer.analyze(caller, List.of(root), List.of());
        assertTrue(result.parseComplete());
        assertEquals(3, result.callSites().stream().filter(c -> c.resolutionStatus().equals("RESOLVED")).count());
        assertTrue(result.callSites().stream().filter(c -> c.expression().contains("join")).findFirst().orElseThrow().parameterTypes().get(0).contains("..."));
        assertEquals("long", result.callSites().stream().filter(c -> c.expression().contains("widen")).findFirst().orElseThrow().parameterTypes().get(0));
    }
}
