package com.wynk.codecontext.port.contract.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit coverage for Kotlin ABI binary keys mapped into V5 semantic impact routing. */
class KotlinAbiImpactRoutingTest {

    @Test
    void mapsBinaryDescriptorsToSemanticSymbolKeys() {
        assertEquals("org.acme.Money#convert(java.lang.String)",
                KotlinAbiSurface.toSemanticSymbolKey("org.acme.Money#convert(Ljava/lang/String;)Ljava/lang/String;"));
        assertEquals("org.acme.Money#convert(int)",
                KotlinAbiSurface.toSemanticSymbolKey("org.acme.Money#convert(I)Ljava/lang/String;"));
        assertEquals("org.acme.Money#Money(java.lang.String)",
                KotlinAbiSurface.toSemanticSymbolKey("org.acme.Money#<init>(Ljava/lang/String;)V"));
        assertEquals("app.ExtensionsKt#decorate(java.lang.String)",
                KotlinAbiSurface.toSemanticSymbolKey("app.ExtensionsKt#decorate(Ljava/lang/String;)Ljava/lang/String;"));
        assertNull(KotlinAbiSurface.toSemanticSymbolKey("not-a-binary-key"));
        assertNull(KotlinAbiSurface.toSemanticSymbolKey(null));
    }

    @Test
    void extractsRemovedSemanticKeysFromEmbeddedMavenSignatures() {
        String before = """
                public API
                KOTLIN_BINARY org.acme.Money#convert(Ljava/lang/String;)Ljava/lang/String;
                KOTLIN_BINARY org.acme.Money#convert(I)Ljava/lang/String;
                KOTLIN_BINARY org.acme.Money#getLabel()Ljava/lang/String;
                """;
        String after = """
                public API
                KOTLIN_BINARY org.acme.Money#convert(I)Ljava/lang/String;
                KOTLIN_BINARY org.acme.Money#getLabel()Ljava/lang/Integer;
                """;
        assertEquals(List.of(
                        "org.acme.Money#convert(Ljava/lang/String;)Ljava/lang/String;",
                        "org.acme.Money#getLabel()Ljava/lang/String;"),
                KotlinAbiSurface.removedBinaryKeys(before, after));
        assertEquals(List.of(
                        "org.acme.Money#convert(java.lang.String)",
                        "org.acme.Money#getLabel()"),
                KotlinAbiSurface.removedSemanticKeys(before, after));
    }

    @Test
    void returnDescriptorOnlyChangeIsABinaryRemovalWithoutFalseSourceSafety() {
        String before = "KOTLIN_BINARY lib.Library#convert(Ljava/lang/String;)Ljava/lang/String;";
        String after = "KOTLIN_BINARY lib.Library#convert(Ljava/lang/String;)Ljava/lang/Integer;";
        assertEquals(List.of("lib.Library#convert(Ljava/lang/String;)Ljava/lang/String;"),
                KotlinAbiSurface.removedBinaryKeys(before, after));
        // Semantic key ignores return type; callers of convert(String) still need routing evidence.
        assertEquals(List.of("lib.Library#convert(java.lang.String)"),
                KotlinAbiSurface.removedSemanticKeys(before, after));
    }

    @Test
    void baselineVsHeadRoutesConstructorsAccessorsExtensionsTopLevelAndOverloads() {
        String before = """
                KOTLIN_BINARY org.acme.kotlin.Amount#<init>(Ljava/lang/String;)V
                KOTLIN_BINARY org.acme.kotlin.Amount#getLabel()Ljava/lang/String;
                KOTLIN_BINARY org.acme.kotlin.Amount#convert(Ljava/lang/String;)Ljava/lang/String;
                KOTLIN_BINARY org.acme.kotlin.Amount#convert(I)Ljava/lang/String;
                KOTLIN_BINARY org.acme.kotlin.AmountKt#decorate(Ljava/lang/String;)Ljava/lang/String;
                KOTLIN_BINARY org.acme.kotlin.AmountKt#entryPoint()Ljava/lang/String;
                """;
        String after = """
                KOTLIN_BINARY org.acme.kotlin.Amount#<init>(I)V
                KOTLIN_BINARY org.acme.kotlin.Amount#getLabel()I
                KOTLIN_BINARY org.acme.kotlin.Amount#convert(Ljava/math/BigDecimal;)Ljava/lang/String;
                KOTLIN_BINARY org.acme.kotlin.Amount#convert(I)Ljava/lang/String;
                """;
        assertEquals(List.of(
                        "org.acme.kotlin.Amount#<init>(Ljava/lang/String;)V",
                        "org.acme.kotlin.Amount#convert(Ljava/lang/String;)Ljava/lang/String;",
                        "org.acme.kotlin.Amount#getLabel()Ljava/lang/String;",
                        "org.acme.kotlin.AmountKt#decorate(Ljava/lang/String;)Ljava/lang/String;",
                        "org.acme.kotlin.AmountKt#entryPoint()Ljava/lang/String;"),
                KotlinAbiSurface.removedBinaryKeys(before, after));
        assertEquals(List.of(
                        "org.acme.kotlin.Amount#Amount(java.lang.String)",
                        "org.acme.kotlin.Amount#convert(java.lang.String)",
                        "org.acme.kotlin.Amount#getLabel()",
                        "org.acme.kotlin.AmountKt#decorate(java.lang.String)",
                        "org.acme.kotlin.AmountKt#entryPoint()"),
                KotlinAbiSurface.removedSemanticKeys(before, after));
        assertFalse(KotlinAbiSurface.removedSemanticKeys(before, after)
                .contains("org.acme.kotlin.Amount#convert(int)"));
    }
}
