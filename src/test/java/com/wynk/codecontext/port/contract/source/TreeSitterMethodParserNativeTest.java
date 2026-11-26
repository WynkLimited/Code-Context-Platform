package com.wynk.codecontext.port.contract.source;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeSitterMethodParserNativeTest {
    @Test void nativeKotlinDefinitionUsesUtf16CharacterCoordinatesAfterUnicode() {
        var parser=TreeSitterMethodParser.tryCreate();
        assertNotNull(parser,"native grammar must load in the supported test runtime");
        String source="// 退款😀\nclass Money {\n fun convert(value: String): String { return value }\n}\n";
        var methods=parser.parse(source,"Money.kt");
        assertEquals(1,methods.size());
        assertEquals("fun convert(value: String): String { return value }",methods.getFirst().rawBody());
        assertTrue(parser.parse("fun broken(","Broken.kt").isEmpty(),"malformed Kotlin must not be labelled complete native evidence");
        assertEquals(3,methods.getFirst().range().startLine());assertEquals(3,methods.getFirst().range().endLine());
    }
}
