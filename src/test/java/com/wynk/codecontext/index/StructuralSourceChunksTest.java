package com.wynk.codecontext.index;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructuralSourceChunksTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("fixtures")
    void completeDefinitionRangesComeFromTheActualBundledGrammar(String extension,String source,String expected) {
        var chunks=StructuralSourceChunks.parse(extension,source,"Policy."+extension);
        assertFalse(chunks.isEmpty(),()->nativeDiagnostics(extension,source));
        assertTrue(chunks.stream().anyMatch(d->d.kind().equals("METHOD")&&d.name().equals(expected)),chunks.toString());
        assertTrue(chunks.stream().filter(d->d.kind().equals("METHOD")).allMatch(d->d.signature().contains(expected)),"context header must retain the actual method identity");
        for(var chunk:chunks) {
            assertEquals(source.substring(chunk.source().startOffset(),chunk.source().endOffset()),chunk.source().text());
            assertTrue(chunk.source().lastLine()>=chunk.source().firstLine());
        }
        // Every non-whitespace character, including imports/state/suffixes, must be represented.
        for(int i=0;i<source.length();i++)if(!Character.isWhitespace(source.charAt(i))) {
            final int offset=i;
            assertTrue(chunks.stream().anyMatch(d->d.source().startOffset()<=offset&&d.source().endOffset()>offset),extension+" missing offset "+i);
        }
    }
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> fixtures() {
        return java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of("kt","class Policy {\n fun refund(amount: Int): Int {\n return amount\n }\n}","refund"),
            org.junit.jupiter.params.provider.Arguments.of("py","class Policy:\n  def refund(self, amount):\n    return amount\n","refund"),
            org.junit.jupiter.params.provider.Arguments.of("js","class Policy { refund(amount) { return amount; } }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("ts","class Policy { refund(amount: number): number { return amount; } }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("tsx","function refund(amount: number) { return amount; }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("go","package policy\nfunc refund(amount int) int { return amount }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("rs","fn refund(amount: i32) -> i32 { amount }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("c","int refund(int amount) { return amount; }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("cpp","class Policy { public: int refund(int amount) { return amount; } };","refund"),
            org.junit.jupiter.params.provider.Arguments.of("cs","class Policy { int refund(int amount) { return amount; } }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("rb","class Policy\n def refund(amount)\n amount\n end\nend","refund"),
            org.junit.jupiter.params.provider.Arguments.of("scala","object Policy { def refund(amount: Int): Int = amount }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("php","<?php function refund($amount) { return $amount; }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("swift","func refund(_ amount: Int) -> Int { return amount }","refund"),
            org.junit.jupiter.params.provider.Arguments.of("lua","function refund(amount)\n return amount\nend","refund"),
            org.junit.jupiter.params.provider.Arguments.of("sh","refund() { printf '%s' \"$1\"; }","refund"));
    }
    private static String nativeDiagnostics(String extension,String source) {
        String language=switch(extension) {
            case "kt"->"KOTLIN";case "py"->"PYTHON";case "js"->"JAVASCRIPT";case "ts"->"TYPESCRIPT";
            case "cs"->"C_SHARP";case "rb"->"RUBY";case "rs"->"RUST";case "sh"->"BASH";
            default->extension.toUpperCase(java.util.Locale.ROOT);
        };
        try(var parser=com.wynk.codecontext.port.contract.source.NativeSyntax.Parser.builder()
                .language(com.wynk.codecontext.port.contract.source.NativeSyntax.Language.valueOf(language)).build();var tree=parser.parse(source)) {
            var queue=new java.util.ArrayDeque<com.wynk.codecontext.port.contract.source.NativeSyntax.Node>();queue.add(tree.getRootNode());
            var nodes=new java.util.ArrayList<String>();
            while(!queue.isEmpty()&&nodes.size()<40){var node=queue.remove();nodes.add(node.getType()+"@"+node.getStartByte()+":"+node.getEndByte());queue.addAll(node.getNamedChildren());}
            return extension+" error="+tree.getRootNode().hasError()+" nodes="+nodes;
        }
    }
    @Test void unicodeByteOffsetsAreConvertedToJavaCharacterOffsets() {
        String source="class Policy:\n  label = '退款😀'\n  def refund(self, amount):\n    return amount\n";
        var chunks=StructuralSourceChunks.parse("py",source,"Policy.py");
        var method=chunks.stream().filter(d->d.name().equals("refund")).findFirst().orElseThrow();
        assertEquals(3,method.source().firstLine());assertEquals(4,method.source().lastLine());
        assertEquals("def refund(self, amount):\n    return amount",method.source().text());
    }
    @Test void malformedAndUnsupportedSourcesUseTheDocumentedFallback() {
        assertTrue(StructuralSourceChunks.parse("py","def broken(","bad.py").isEmpty());
        assertTrue(StructuralSourceChunks.parse("unknown","class C {}","bad.unknown").isEmpty());
    }
}
