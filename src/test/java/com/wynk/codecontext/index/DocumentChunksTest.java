package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentChunksTest {
    @Test void aSingleHeadingRetainsItsSectionInsteadOfUsingFallbackLineWindows() {
        String text="# Single section\n"+"short note\n".repeat(80);
        var chunks=DocumentChunks.split("README.md",text,1,6000);
        assertEquals(1,chunks.size());assertEquals(81,chunks.getFirst().lastLine());
    }
    @Test void markdownUsesActualHeadingsAndPreservesCodeExamplesAndPreamble() {
        String text="Introduction\r\n\r\n# Setup\r\n```markdown\r\n# not a heading\r\n```\r\n\r\nUsage\r\n=====\r\nRun it.\r\n";
        var chunks=DocumentChunks.split("README.md",text,1,6000);
        assertEquals(List.of(1,3,8),chunks.stream().map(BoundedTextChunks.Piece::firstLine).toList());
        assertEquals(List.of(2,7,10),chunks.stream().map(BoundedTextChunks.Piece::lastLine).toList());
        assertTrue(chunks.get(1).text().contains("# not a heading"));
        assertEquals(text.replace("\r\n","\n").stripTrailing(),String.join("\n",chunks.stream().map(BoundedTextChunks.Piece::text).toList()).stripTrailing());
    }
    @Test void headingsInsideQuotesAndIndentedCodeAreNotDocumentSections() {
        var chunks=DocumentChunks.split("notes.markdown","# Main\n> # quotation\n\n    # code\n\n## Next\nEnd",5,6000);
        assertEquals(List.of(5,10),chunks.stream().map(BoundedTextChunks.Piece::firstLine).toList());
        assertTrue(chunks.getFirst().text().contains("> # quotation"));
    }
    @Test void largeYamlSectionsRetainRawValuesCommentsAndSourceLocations() {
        String text="# application\nserver:\n  description: '"+"s".repeat(250)+"'\n# indexing\nindexing:\n  description: '"+"i".repeat(250)+"'\n";
        var chunks=DocumentChunks.split("application.yml",text,1,512);
        assertEquals(List.of(1,2,5),chunks.stream().map(BoundedTextChunks.Piece::firstLine).toList());
        assertEquals(text.stripTrailing(),String.join("\n",chunks.stream().map(BoundedTextChunks.Piece::text).toList()));
        assertTrue(chunks.stream().allMatch(chunk->chunk.text().length()<=512));
    }
    @Test void smallConfigurationStaysOneChunkEvenWithManyShortLines() {
        String text="settings:\n"+"  - short value\n".repeat(80);
        assertEquals(1,DocumentChunks.split("settings.yaml",text,1,6000).size());
        assertEquals(81,DocumentChunks.split("settings.yaml",text,1,6000).getFirst().lastLine());
    }
    @Test void malformedLargeConfigurationFallsBackWithoutDroppingAnySource() {
        String text="bad: [\n"+"  bad content\n".repeat(80);
        var chunks=DocumentChunks.split("bad.yaml",text,1,512);
        assertTrue(chunks.size()>1);
        assertTrue(chunks.stream().allMatch(chunk->chunk.text().length()<=512&&chunk.lastLine()-chunk.firstLine()<60));
        assertEquals(1,chunks.getFirst().firstLine());assertEquals(81,chunks.getLast().lastLine());
    }
}
