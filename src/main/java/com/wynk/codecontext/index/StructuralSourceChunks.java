package com.wynk.codecontext.index;

import com.wynk.codecontext.port.contract.source.NativeSyntax.Language;
import com.wynk.codecontext.port.contract.source.NativeSyntax.Node;
import com.wynk.codecontext.port.contract.source.NativeSyntax.Parser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Complete parser ranges, never next-declaration guesses. Unknown/error syntax falls back.
 * Parsers and trees are per-file and closed, avoiding shared native parser/thread state. */
final class StructuralSourceChunks {
    private static final boolean NATIVE_AVAILABLE=loadNative();
    private static final Map<String,String> LANGUAGES=Map.ofEntries(
            Map.entry("kt","KOTLIN"),Map.entry("kts","KOTLIN"),
            Map.entry("py","PYTHON"),Map.entry("js","JAVASCRIPT"),Map.entry("jsx","JAVASCRIPT"),
            Map.entry("mjs","JAVASCRIPT"),Map.entry("ts","TYPESCRIPT"),Map.entry("tsx","TSX"),
            Map.entry("go","GO"),Map.entry("rs","RUST"),Map.entry("c","C"),Map.entry("h","C"),
            Map.entry("cpp","CPP"),Map.entry("cc","CPP"),Map.entry("hpp","CPP"),
            Map.entry("cs","C_SHARP"),Map.entry("rb","RUBY"),Map.entry("rake","RUBY"),
            Map.entry("cjs","JAVASCRIPT"),Map.entry("mts","TYPESCRIPT"),Map.entry("cts","TYPESCRIPT"),
            Map.entry("cxx","CPP"),Map.entry("cu","CPP"),Map.entry("cuh","CPP"),Map.entry("metal","CPP"),
            Map.entry("scala","SCALA"),Map.entry("php","PHP"),Map.entry("swift","SWIFT"),
            Map.entry("lua","LUA"),Map.entry("sh","BASH"),Map.entry("bash","BASH"));
    private static final Set<String> CALLABLES=Set.of("function_declaration","function_definition_statement","function_definition","method_declaration",
            "method_definition","method_signature","abstract_method_signature","generator_function_declaration","constructor_declaration","secondary_constructor","function_item","method","singleton_method");
    private static final Set<String> SCOPES=Set.of("class_declaration","class_definition","interface_declaration",
            "object_declaration","object_definition","trait_definition","enum_definition","given_definition","record_declaration","enum_declaration","protocol_declaration","class_specifier","struct_specifier","struct_item","trait_item","impl_item","class","module");
    record Declaration(String kind,String name,String signature,String owner,JavaSourceText.Slice source) {}
    static List<Declaration> parse(String extension,String source,String path) {
        String language=LANGUAGES.get(extension);
        if(!NATIVE_AVAILABLE||language==null||source.isBlank())return List.of();
        var coordinates=new JavaSourceText(source);
        try(Parser parser=Parser.builder().language(Language.valueOf(language)).build()) {
            parser.setTimeout(Duration.ofSeconds(2));
            try(var tree=parser.parse(source)) {
                if(tree.getRootNode().hasError())return List.of();
                var declarations=new ArrayList<Declaration>();
                collect(tree.getRootNode(),path,source,coordinates,declarations,0);
                if(declarations.isEmpty())return List.of();
                // Retain imports, state between methods, comments, and trailing source too.
                var ordered=declarations.stream().sorted(java.util.Comparator.comparingInt(d->d.source().startOffset())).toList();
                var result=new ArrayList<Declaration>();int position=0;
                for(var declaration:ordered) {
                    var part=declaration.source();
                    if(part.startOffset()<position)return List.of(); // unrepresentable overlap: honest fallback
                    addGap(result,source,path,position,part.startOffset(),coordinates);
                    result.add(declaration);position=part.endOffset();
                }
                addGap(result,source,path,position,source.length(),coordinates);
                return List.copyOf(result);
            }
        } catch(LinkageError|RuntimeException failure) {return List.of();}
    }
    private static void collect(Node node,String owner,String source,JavaSourceText coordinates,List<Declaration> out,int depth) {
        if(depth>256)throw new IllegalArgumentException("structural nesting limit exceeded");
        String type=node.getType();
        if(CALLABLES.contains(type)) {
            String name=name(node,source);
            var slice=coordinates.range(character(node.getStartByte()),character(node.getEndByte()));
            // Full original declaration header disambiguates overloads and generic signatures.
            Node body=node.getChildByFieldName("body");
            if(body==null||body.isNull())body=node.getNamedChildren().stream()
                    .filter(child->Set.of("function_body","block","compound_statement","statement_block","body_statement").contains(child.getType()))
                    .findFirst().orElse(null);
            String signature=body==null||body.isNull()?name:source.substring(slice.startOffset(),character(body.getStartByte())).strip();
            out.add(new Declaration("METHOD",name,signature,owner,slice));return;
        }
        String nextOwner=owner;
        if(SCOPES.contains(type)) {
            String name=name(node,source);nextOwner=owner+"."+name;
            int start=character(node.getStartByte()),end=character(node.getEndByte());
            int first=firstCallableStart(node,depth);
            if(first<end)end=first;
            out.add(new Declaration("CLASS",name,nextOwner,null,coordinates.range(start,end)));
            if(first==Integer.MAX_VALUE)return;
        }
        for(var child:node.getNamedChildren())collect(child,nextOwner,source,coordinates,out,depth+1);
    }
    private static int firstCallableStart(Node node,int depth) {
        if(depth>256)throw new IllegalArgumentException("structural nesting limit exceeded");
        int start=Integer.MAX_VALUE;
        for(var child:node.getNamedChildren()) {
            if(CALLABLES.contains(child.getType())||SCOPES.contains(child.getType()))start=Math.min(start,character(child.getStartByte()));
            else start=Math.min(start,firstCallableStart(child,depth+1));
        }
        return start;
    }
    private static boolean loadNative() {
        try {com.wynk.codecontext.port.contract.source.NativeSyntax.requireAvailable();return true;}
        catch(LinkageError|RuntimeException unavailable) {return false;}
    }
    private static String name(Node node,String source) {
        if(node.getChildCount()==0&&node.getType().endsWith("identifier"))
            return source.substring(character(node.getStartByte()),character(node.getEndByte()));
        Node name=node.getChildByFieldName("name");
        if(name!=null&&!name.isNull())return source.substring(character(name.getStartByte()),character(name.getEndByte()));
        Node declarator=node.getChildByFieldName("declarator");
        if(declarator!=null&&!declarator.isNull())return name(declarator,source);
        for(var child:node.getNamedChildren())if(Set.of("simple_identifier","type_identifier","identifier").contains(child.getType()))
            return source.substring(character(child.getStartByte()),character(child.getEndByte()));
        return node.getType();
    }
    // NativeSyntax normalizes byte positions to UTF-16 character indices.
    private static int character(int parserOffset) {return parserOffset;}
    private static void addGap(List<Declaration> out,String source,String path,int start,int end,JavaSourceText coordinates) {
        if(!source.substring(start,end).isBlank())out.add(new Declaration("FILE",path,path,null,coordinates.range(start,end)));
    }
}
