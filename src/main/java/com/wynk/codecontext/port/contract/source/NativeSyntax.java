package com.wynk.codecontext.port.contract.source;

import org.treesitter.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Small platform-portable JNI adapter. All public offsets are Java UTF-16 character
 * indices, independent of the underlying binding's byte coordinates. Trees own node lifetimes. */
public final class NativeSyntax {
    private NativeSyntax() {}
    public enum Language {
        JAVA(TreeSitterJava::new), KOTLIN(TreeSitterKotlin::new), PYTHON(TreeSitterPython::new),
        JAVASCRIPT(TreeSitterJavascript::new), TYPESCRIPT(TreeSitterTypescript::new), TSX(TreeSitterTsx::new),
        GO(TreeSitterGo::new), RUST(TreeSitterRust::new), C(TreeSitterC::new), CPP(TreeSitterCpp::new),
        C_SHARP(TreeSitterCSharp::new), RUBY(TreeSitterRuby::new), SCALA(TreeSitterScala::new),
        PHP(TreeSitterPhp::new), SWIFT(TreeSitterSwift::new), LUA(TreeSitterLua::new), BASH(TreeSitterBash::new);
        final Supplier<TSLanguage> grammar;
        Language(Supplier<TSLanguage> grammar){this.grammar=grammar;}
    }
    public static void requireAvailable(){try(var ignored=Parser.builder().language(Language.JAVA).build()) {}}
    public static final class Parser implements AutoCloseable {
        private final TSParser parser;
        private long timeoutNanos=Duration.ofSeconds(2).toNanos();
        private Parser(Language language) {
            parser=new TSParser();
            try {
                if(!parser.setLanguage(language.grammar.get()))throw new IllegalArgumentException("unsupported native grammar ABI");
            } catch(RuntimeException|LinkageError failure){parser.close();throw failure;}
        }
        public static Builder builder(){return new Builder();}
        public void setTimeout(Duration duration){setTimeout(duration.toNanos(),TimeUnit.NANOSECONDS);}
        public void setTimeout(long time,TimeUnit unit) {
            timeoutNanos=unit.toNanos(time);
            if(timeoutNanos<1)throw new IllegalArgumentException("parser deadline must be positive");
        }
        public Tree parse(String source) {
            byte[] input=source.getBytes(StandardCharsets.UTF_8);
            int[] offsets=null;
            if(input.length!=source.length()) {
                offsets=new int[input.length+1];
                for(int chars=0,bytes=0;chars<source.length();) {
                    int cp=source.codePointAt(chars);
                    int width=cp<=127?1:cp<=2047?2:cp>=0xd800&&cp<=0xdfff?1:cp<=65535?3:4;
                    java.util.Arrays.fill(offsets,bytes,bytes+width,chars);
                    bytes+=width;chars+=Character.charCount(cp);offsets[bytes]=chars;
                }
            }
            long deadline=System.nanoTime()+timeoutNanos;
            boolean[] cancelled={false};
            TSParserProgress progress=state->{cancelled[0]=Thread.currentThread().isInterrupted()||System.nanoTime()-deadline>=0;return cancelled[0];};
            TSReader reader=(buffer,offset,position)->{
                if(progress.progress(null)||offset>=input.length)return 0;
                int count=Math.min(buffer.length,input.length-offset);
                System.arraycopy(input,offset,buffer,0,count);return count;
            };
            TSTree tree=parser.parseWithOptions(new byte[4096],null,reader,TSInputEncoding.TSInputEncodingUTF8,progress);
            if(cancelled[0]){tree.close();parser.reset();throw new IllegalStateException("native parser cancelled or deadline exceeded");}
            return new Tree(tree,offsets);
        }
        @Override public void close(){parser.close();}
        public static final class Builder {
            private Language language;
            public Builder language(Language language){this.language=language;return this;}
            public Parser build(){return new Parser(java.util.Objects.requireNonNull(language));}
        }
    }
    public static final class Tree implements AutoCloseable {
        private final TSTree tree;
        private final int[] offsets;
        private Tree(TSTree tree,int[] offsets){this.tree=tree;this.offsets=offsets;}
        public Node getRootNode(){return new Node(tree.getRootNode(),offsets);}
        @Override public void close(){tree.close();}
    }
    public static final class Node {
        private final TSNode node;
        private final int[] offsets;
        private Node(TSNode node,int[] offsets){this.node=node;this.offsets=offsets;}
        public String getType(){return node.getType();}
        public boolean hasError(){return node.hasError();}
        public boolean isNull(){return node.isNull();}
        public int getStartByte(){return characters(node.getStartByte());}
        public int getEndByte(){return characters(node.getEndByte());}
        private int characters(int offset) {return offsets==null?offset:offsets[offset];}
        public int getChildCount(){return node.getChildCount();}
        public Node getChildByFieldName(String field) {
            TSNode child=node.getChildByFieldName(field);return child==null||child.isNull()?null:new Node(child,offsets);
        }
        public List<Node> getNamedChildren() {
            var children=new ArrayList<Node>(node.getNamedChildCount());
            for(int i=0;i<node.getNamedChildCount();i++)children.add(new Node(node.getNamedChild(i),offsets));
            return children;
        }
        public List<Node> getChildren(){return getNamedChildren();}
    }
}
