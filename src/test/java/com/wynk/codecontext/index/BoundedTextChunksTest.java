package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedTextChunksTest {
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
