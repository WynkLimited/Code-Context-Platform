package com.wynk.codecontext.index;

import org.commonmark.node.Heading;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.nodes.MappingNode;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/** Document sections come from parser source locations, never reserialized configuration values. */
final class DocumentChunks {
    static List<BoundedTextChunks.Piece> split(String path,String text,int startLine,int maxChars) {
        String lower=path.toLowerCase(Locale.ROOT);
        boolean markdown=lower.endsWith(".md")||lower.endsWith(".markdown");
        boolean configuration=lower.endsWith(".yaml")||lower.endsWith(".yml")||lower.endsWith(".json");
        if(configuration&&text.length()<=Math.max(512,maxChars))return BoundedTextChunks.split(text,startLine,maxChars);
        if(!markdown&&!configuration)return BoundedTextChunks.split(text,startLine,maxChars,60);
        var boundaries=new TreeSet<Integer>();boundaries.add(0);boolean structured=false;
        try {
            if(markdown) {
                var document=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS)
                        .maxOpenBlockParsers(100).maxInlineNesting(100).build().parse(text);
                for(var node=document.getFirstChild();node!=null;node=node.getNext()) {
                    if(node instanceof Heading&&!node.getSourceSpans().isEmpty()) {
                        boundaries.add(node.getSourceSpans().getFirst().getLineIndex());
                        structured=true;
                    }
                }
            } else {
                var options=new LoaderOptions();options.setCodePointLimit(1024*1024);
                options.setNestingDepthLimit(100);options.setMaxAliasesForCollections(50);
                // Compose nodes only: no object construction, configuration execution or external reads.
                for(var document:new Yaml(options).composeAll(new StringReader(text))) {
                    if(document instanceof MappingNode mapping) {
                        structured=true;
                        for(var entry:mapping.getValue())boundaries.add(entry.getKeyNode().getStartMark().getLine());
                    }
                }
            }
        } catch(RuntimeException malformed) {return BoundedTextChunks.split(text,startLine,maxChars,60);}
        if(boundaries.size()==1)return structured?BoundedTextChunks.split(text,startLine,maxChars)
                :BoundedTextChunks.split(text,startLine,maxChars,60);
        List<String> lines=text.lines().toList();boundaries.add(lines.size());
        var points=new ArrayList<>(boundaries);var chunks=new ArrayList<BoundedTextChunks.Piece>();
        for(int i=0;i<points.size()-1;i++) {
            int begin=points.get(i),end=points.get(i+1);
            // Terminate the slice so a real trailing blank line is not mistaken for a delimiter.
            if(begin<end)chunks.addAll(BoundedTextChunks.split(String.join("\n",lines.subList(begin,end))+"\n",startLine+begin,maxChars));
        }
        return List.copyOf(chunks);
    }
}
