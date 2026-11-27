package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedTextChunksTest {
    @Test void fallbackWindowsUseSixtyLinesAndTenLinesOfOverlap() {
        String text=java.util.stream.IntStream.rangeClosed(1,121).mapToObj(i->"source line "+i).collect(java.util.stream.Collectors.joining("\n"));
        var chunks=BoundedTextChunks.split(text,1,6000,60);
        assertEquals(3,chunks.size());
        assertEquals(java.util.List.of(1,51,101),chunks.stream().map(BoundedTextChunks.Piece::firstLine).toList());
        assertEquals(java.util.List.of(60,110,121),chunks.stream().map(BoundedTextChunks.Piece::lastLine).toList());
        for(var chunk:chunks)assertEquals(chunk.lastLine()-chunk.firstLine()+1,chunk.text().lines().count());
    }
    @Test void oneLineWindowsAdvanceWithoutRetainingOverlap() {
        var chunks=BoundedTextChunks.split("first\nsecond\nthird\n",7,512,1);
        assertEquals(java.util.List.of(7,8,9),chunks.stream().map(BoundedTextChunks.Piece::firstLine).toList());
        assertTrue(chunks.stream().allMatch(chunk->chunk.firstLine()==chunk.lastLine()));
        assertThrows(IllegalArgumentException.class,()->BoundedTextChunks.split("text",1,512,0));
    }
    @Test void overlappingLongLinesNeverExceedTheLimitOrLoseSource() {
        var lines=java.util.stream.IntStream.range(0,30).mapToObj(i->"line-"+i+":"+"x".repeat(180)).toList();
        var chunks=BoundedTextChunks.split(String.join("\n",lines),5,512);
        assertTrue(chunks.stream().allMatch(chunk->chunk.text().length()<=512),chunks.toString());
        for(String line:lines)assertTrue(chunks.stream().anyMatch(chunk->chunk.text().contains(line)),line);
        assertEquals(5,chunks.getFirst().firstLine());assertEquals(34,chunks.getLast().lastLine());
        for(int i=1;i<chunks.size();i++)assertTrue(chunks.get(i).firstLine()>chunks.get(i-1).firstLine(),"every emitted overlap window must advance");
    }
    @Test void aTerminalNewlineDoesNotInventAnExtraSourceLine() {
        var chunks=BoundedTextChunks.split("first\r\nsecond\r\n",8,512);
        assertEquals(1,chunks.size());assertEquals(8,chunks.getFirst().firstLine());assertEquals(9,chunks.getFirst().lastLine());
    }
    @Test void longLineFragmentsDoNotSplitUnicodeSurrogatePairs() {
        var chunks=BoundedTextChunks.split("😀a".repeat(500),4,512);
        assertTrue(chunks.size()>1);
        for(var chunk:chunks) {
            assertTrue(chunk.text().length()<=512);
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length()-1)));
            assertEquals(4,chunk.firstLine());assertEquals(4,chunk.lastLine());
        }
    }
}
