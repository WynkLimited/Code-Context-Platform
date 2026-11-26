package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingInputChunksTest {
    private static final String HEADER="repo: fixture/library | file: Money.java\nclass: acme.Money\nmethod: convert(String)\n---\n";
    @Test void unchangedSmallInputRetainsExactBytesAndPhysicalRanges() {
        String input="first\r\nsecond\r\n";
        var pieces=EmbeddingInputChunks.split(input,7,HEADER,8192);
        assertEquals(1,pieces.size());assertEquals(input,pieces.getFirst().text());
        assertEquals(7,pieces.getFirst().firstLine());assertEquals(8,pieces.getFirst().lastLine());
    }
    @Test void unchangedDocumentSectionsRetainExplicitTrailingBlankLineRanges() {
        var parts=DocumentChunks.split("guide.md","# Setup\nUse local tools.\n\n## Recovery\nRestore the saved snapshot.\n",1,6000).stream()
                .flatMap(p->EmbeddingInputChunks.split(p.text(),p.firstLine(),p.lastLine(),HEADER,8192).stream()).toList();
        assertEquals(java.util.List.of("1:3","4:5"),parts.stream().map(p->p.firstLine()+":"+p.lastLine()).toList());
    }
    @Test void unicodeLongLinesStayWithinCompleteInputBudgetWithoutLosingCodePoints() {
        String input="😀中".repeat(2000);
        var pieces=EmbeddingInputChunks.split(input,13,HEADER,8192);
        assertTrue(pieces.size()>1);
        assertEquals(input,pieces.stream().map(BoundedTextChunks.Piece::text).collect(Collectors.joining()));
        for(var piece:pieces) {
            assertTrue(EmbeddingInputChunks.estimate(HEADER+piece.text())+32<=1500);
            assertFalse(Character.isLowSurrogate(piece.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(piece.text().charAt(piece.text().length()-1)));
            assertEquals(13,piece.firstLine());assertEquals(13,piece.lastLine());
        }
    }
    @Test void blankBoundariesArePreferredAndEveryLineRetainsItsSourceLocation() {
        String input=IntStream.rangeClosed(1,201).mapToObj(i->i%20==0?"":"line "+i+" "+"x".repeat(25)).collect(Collectors.joining("\n"));
        var pieces=EmbeddingInputChunks.split(input,3,HEADER,8192);
        assertTrue(pieces.getFirst().text().endsWith("\n\n"));
        String[] lines=input.split("\n",-1);
        for(var piece:pieces) {
            assertTrue(EmbeddingInputChunks.estimate(HEADER+piece.text())+32<=1500);
            String[] content=piece.text().split("\n");
            for(int i=0;i<content.length;i++)assertEquals(lines[piece.firstLine()-3+i],content[i]);
        }
        for(int i=0;i<lines.length;i++) {
            final int line=i+3;
            assertTrue(pieces.stream().anyMatch(p->p.firstLine()<=line&&p.lastLine()>=line),"source line "+line);
        }
        assertEquals(pieces,EmbeddingInputChunks.split(input,3,HEADER,8192));
    }
    @Test void effectiveModelContextAndMetadataReduceTheSourceBudget() {
        var pieces=EmbeddingInputChunks.split("a".repeat(2000),1,HEADER,512);
        assertTrue(pieces.stream().allMatch(p->EmbeddingInputChunks.estimate(HEADER+p.text())+32<=512));
        assertThrows(IllegalArgumentException.class,()->EmbeddingInputChunks.split("text",1,"x".repeat(500),512));
    }
    @Test void configurationSectionsRetainEveryCharacterAfterBothSizingStages() {
        String source="server:\n  description: '"+"server ".repeat(450)+"'\nindexer:\n  description: '"+"indexer ".repeat(450)+"'\n";
        var pieces=DocumentChunks.split("config/runtime.yaml",source,1,6000).stream()
                .flatMap(p->EmbeddingInputChunks.split(p.text(),p.firstLine(),HEADER,8192).stream()).toList();
        String rebuilt=java.util.stream.Stream.of(1,3).map(section->pieces.stream()
                .filter(p->p.firstLine()>=section&&p.lastLine()<=section+1)
                .map(BoundedTextChunks.Piece::text).collect(Collectors.joining())).collect(Collectors.joining("\n"));
        assertEquals(source.stripTrailing(),rebuilt.stripTrailing());
        assertTrue(pieces.stream().allMatch(p->EmbeddingInputChunks.estimate(HEADER+p.text())+32<=1500));
    }
    @Test void noBlankLineInputMakesProgressWithBoundedOverlap() {
        String input=IntStream.range(0,200).mapToObj(i->"line"+i+" "+"x".repeat(50)).collect(Collectors.joining("\n"));
        var pieces=EmbeddingInputChunks.split(input,1,HEADER,8192);
        for(int i=1;i<pieces.size();i++) {
            assertTrue(pieces.get(i).firstLine()>pieces.get(i-1).firstLine());
            assertTrue(pieces.get(i-1).lastLine()-pieces.get(i).firstLine()+1<=10);
        }
        assertEquals(200,pieces.getLast().lastLine());
    }
}
