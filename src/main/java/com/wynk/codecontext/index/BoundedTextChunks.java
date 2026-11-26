package com.wynk.codecontext.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounded physical source-line windows; overlap may shrink to keep the next window within budget. */
final class BoundedTextChunks {
    record Piece(String text,int firstLine,int lastLine) {}
    static List<Piece> split(String text,int startLine,int maxChars) {
        int limit=Math.max(512,maxChars),overlapChars=Math.min(300,limit/10);
        String[] lines=text.split("\\r\\n|\\r|\\n",-1);
        // String.lines()/read_file count the terminating delimiter, not a fictitious extra line.
        if(lines.length>1&&lines[lines.length-1].isEmpty())lines=Arrays.copyOf(lines,lines.length-1);
        var out=new ArrayList<Piece>();var block=new ArrayList<String>();int blockStart=0,size=0;
        for(int i=0;i<lines.length;i++) {
            String line=lines[i];
            if(line.length()>limit) {
                if(!block.isEmpty()) {emit(out,block,startLine+blockStart);block.clear();size=0;}
                int position=0;
                while(position<line.length()) {
                    int end=Math.min(line.length(),position+limit);
                    if(end<line.length()&&Character.isHighSurrogate(line.charAt(end-1))&&Character.isLowSurrogate(line.charAt(end)))end--;
                    out.add(new Piece(line.substring(position,end),startLine+i,startLine+i));
                    if(end==line.length())break;
                    int next=Math.max(position+1,end-overlapChars);
                    if(Character.isLowSurrogate(line.charAt(next))&&next>position+1&&Character.isHighSurrogate(line.charAt(next-1)))next--;
                    position=next;
                }
                blockStart=i+1;continue;
            }
            if(!block.isEmpty()&&size+1+line.length()>limit) {
                emit(out,block,startLine+blockStart);
                int carry=Math.min(10,block.size());
                block=new ArrayList<>(block.subList(block.size()-carry,block.size()));
                size=length(block);
                while(!block.isEmpty()&&size+1+line.length()>limit) {block.removeFirst();size=length(block);}
                blockStart=i-block.size();
            }
            if(block.isEmpty())blockStart=i;
            size+=line.length()+(block.isEmpty()?0:1);block.add(line);
        }
        if(!block.isEmpty())emit(out,block,startLine+blockStart);
        return out.isEmpty()?List.of(new Piece("",startLine,startLine)):List.copyOf(out);
    }
    private static int length(List<String> lines){return lines.stream().mapToInt(String::length).sum()+Math.max(0,lines.size()-1);}
    private static void emit(List<Piece> output,List<String> lines,int start){output.add(new Piece(String.join("\n",lines),start,start+lines.size()-1));}
}
