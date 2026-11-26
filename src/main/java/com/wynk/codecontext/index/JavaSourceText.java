package com.wynk.codecontext.index;

import com.github.javaparser.Position;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

/** Source slices retain physical line coordinates; pretty-printed AST text does not. */
final class JavaSourceText {
    private static final Pattern NEWLINE=Pattern.compile("\\r\\n|\\r|\\n");
    record Slice(String text,int firstLine,int lastLine) {}
    private final String source;
    private final int[] lines;
    JavaSourceText(String source) {
        this.source=source;
        var starts=new ArrayList<Integer>();starts.add(0);
        var matcher=NEWLINE.matcher(source);while(matcher.find())starts.add(matcher.end());
        lines=starts.stream().mapToInt(Integer::intValue).toArray();
    }
    Slice definition(Node node) {
        var range=node.getRange().orElseThrow(()->new IllegalArgumentException("source range unavailable"));
        return slice(begin(node),offset(range.end)+1);
    }
    Slice classHeader(ClassOrInterfaceDeclaration type) {
        int end=offset(type.getRange().orElseThrow().end)+1;
        for(var member:type.getMembers()) {
            if(member instanceof MethodDeclaration||member instanceof ConstructorDeclaration||member instanceof TypeDeclaration<?>) {
                end=Math.min(end,offset(begin(member)));
            }
        }
        return slice(begin(type),end);
    }
    static String ownerOf(Node node) {
        var names=new ArrayList<String>();
        Node parent=node.getParentNode().orElse(null);
        while(parent!=null) {
            if(parent instanceof TypeDeclaration<?> type)names.add(type.getNameAsString());
            parent=parent.getParentNode().orElse(null);
        }
        Collections.reverse(names);
        String packageName=node.findCompilationUnit().flatMap(unit->unit.getPackageDeclaration()).map(p->p.getNameAsString()+".").orElse("");
        return packageName+String.join(".",names);
    }
    private Position begin(Node node) {
        Position begin=node.getBegin().orElseThrow();
        var comment=node.getComment().flatMap(Node::getRange);
        return comment.isPresent()&&comment.get().end.isBefore(begin)?comment.get().begin:begin;
    }
    private Slice slice(Position begin,int end) {
        int start=offset(begin);
        if(end<start||end>source.length())throw new IllegalArgumentException("invalid source range");
        String text=source.substring(start,end).stripTrailing();
        int lineCount=(int)NEWLINE.matcher(text).results().count();
        return new Slice(text,begin.line,begin.line+lineCount);
    }
    private int offset(Position position) {
        if(position.line<1||position.line>lines.length||position.column<1)throw new IllegalArgumentException("invalid source position");
        int offset=lines[position.line-1]+position.column-1;
        if(offset>=source.length())throw new IllegalArgumentException("source position outside file");
        return offset;
    }
}
