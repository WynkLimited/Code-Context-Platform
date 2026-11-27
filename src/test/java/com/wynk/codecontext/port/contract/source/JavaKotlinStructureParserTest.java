package com.wynk.codecontext.port.contract.source;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaKotlinStructureParserTest {
    @Test
    void extractsKotlinOverloadsWithCorrectLinesAfterMultibyteUtf8Text() {
        JavaKotlinStructureParser parser = new JavaKotlinStructureParser();
        String source = """
                // café: the comment has a multibyte character
                class Money {
                    fun convert(value: String): String { return value }
                    fun convert(value: Int): String { return value.toString() }
                }
                """;

        var extraction = parser.extract(source, "src/main/kotlin/org/acme/Money.kt");

        if (!parser.treeSitterEnabled()) {
            assertFalse(extraction.complete(), "without the native Kotlin parser, extraction must be explicitly incomplete");
            return;
        }
        assertTrue(extraction.complete());
        assertEquals(2, extraction.methods().size());
        assertEquals("convert", extraction.methods().get(0).name());
        assertEquals("convert", extraction.methods().get(1).name());
        assertEquals(3, extraction.methods().get(0).range().startLine());
        assertEquals(4, extraction.methods().get(1).range().startLine());
        assertFalse(extraction.methods().get(0).signatureKey().equals(extraction.methods().get(1).signatureKey()));
    }
}
