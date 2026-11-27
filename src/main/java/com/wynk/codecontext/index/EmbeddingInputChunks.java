package com.wynk.codecontext.index;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Conservative byte-level token bound: at most one token per UTF-8 byte, plus 32
 * reserved special tokens. Includes the exact metadata header. Ollama truncation stays disabled.
 * This deliberately favours safety over packing density; it is not a measured tokenizer count. */
final class EmbeddingInputChunks {
    static final int TARGET_TOKENS=1500;
    static int estimate(String text) {return text.getBytes(StandardCharsets.UTF_8).length;}
    static List<BoundedTextChunks.Piece> split(String text,int firstLine,String header,int contextTokens) {
        return split(text,firstLine,firstLine+Math.max(0,(int)text.lines().count()-1),header,contextTokens);
    }
    static List<BoundedTextChunks.Piece> split(String text,int firstLine,int lastLine,String header,int contextTokens) {
        int budget=Math.min(TARGET_TOKENS,contextTokens)-32-estimate(header);
        if(budget<4)throw new IllegalArgumentException("embedding metadata exhausts input budget");
        if(estimate(text)<=budget)return List.of(new BoundedTextChunks.Piece(text,firstLine,
                lastLine));
        text=text.replace("\r\n","\n").replace('\r','\n');
        var output=new ArrayList<BoundedTextChunks.Piece>();
        int start=0,line=firstLine;
        while(start<text.length()) {
            int end=start,bytes=0,lastBlank=-1;
            while(end<text.length()) {
                int cp=text.codePointAt(end),width=cp<=0x7f?1:cp<=0x7ff?2:cp<=0xffff?3:4;
                if(bytes+width>budget)break;
                bytes+=width;end+=Character.charCount(cp);
                if(cp=='\n'&&blankLineEndsAt(text,end))lastBlank=end;
            }
            if(end<text.length()&&lastBlank>start)end=lastBlank;
            // Otherwise prefer a physical line boundary; fragment only when a whole line cannot fit.
            else if(end<text.length()) {
                int newline=text.lastIndexOf('\n',end-1);
                if(newline>=start)end=newline+1;
            }
            String part=text.substring(start,end);
            int newlines=(int)part.chars().filter(c->c=='\n').count();
            int last=line+newlines-(part.endsWith("\n")?1:0);
            output.add(new BoundedTextChunks.Piece(part,line,Math.max(line,last)));
            if(end==text.length())break;
            int next=end;
            // Carry at most ten complete lines and no more than a quarter of the budget.
            // Always advance, including long lines and tiny budgets.
            int candidate=end,carried=0;
            for(int n=0;n<10;n++) {
                int previous=text.lastIndexOf('\n',candidate-2)+1;
                if(previous<=start||previous>=candidate)break;
                int cost=estimate(text.substring(previous,candidate));
                if(carried+cost>budget/4)break;
                carried+=cost;next=previous;candidate=previous;
            }
            line+=(int)text.substring(start,next).chars().filter(c->c=='\n').count();
            start=next;
        }
        return output.isEmpty()?List.of(new BoundedTextChunks.Piece("",firstLine,firstLine)):List.copyOf(output);
    }
    private static boolean blankLineEndsAt(String text,int end) {
        int begin=text.lastIndexOf('\n',end-2)+1;
        return text.substring(begin,end-1).isBlank();
    }
}
