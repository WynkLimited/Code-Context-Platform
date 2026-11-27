package com.wynk.codecontext.index;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.CallableDeclaration;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TinyChunkGroupsTest {
    private static List<TinyChunkGroups.Group> groups(String source,int maxChars) {
        var text=new JavaSourceText(source);
        var definitions=StaticJavaParser.parse(source).findAll(CallableDeclaration.class).stream()
                .map(node->new TinyChunkGroups.Definition(node.isMethodDeclaration()?"METHOD":"CONSTRUCTOR",
                        node.getSignature().asString(),JavaSourceText.ownerOf(node),text.definition(node))).toList();
        return TinyChunkGroups.merge(source,definitions,maxChars);
    }
    @Test void adjacentInlineGettersSettersAndConstructorKeepExactSourceAndEverySignature() {
        String source="class C { C(){} int get(){return 1;} void set(int x){} }";
        var groups=groups(source,6000);
        assertEquals(1,groups.size());
        assertEquals(List.of("C()","get()","set(int)"),groups.getFirst().members().stream().map(TinyChunkGroups.Definition::signature).toList());
        assertEquals("C(){} int get(){return 1;} void set(int x){}",groups.getFirst().source().text());
        assertEquals(1,groups.getFirst().source().firstLine());assertEquals(1,groups.getFirst().source().lastLine());
        assertEquals(groups,groups(source,6000));
    }
    @Test void nestedAndUnrelatedClassesNeverMergeAndInterveningStateIsABarrier() {
        String source="class C { int a(){return 1;} int x; int b(){return 2;} class N { int n(){return 3;} int m(){return 4;} } int c(){return 5;} } class D { int d(){return 6;} }";
        var groups=groups(source,6000);
        assertEquals(5,groups.size());
        assertEquals(List.of("a()","b()","n() | m()","c()","d()"),groups.stream().map(TinyChunkGroups.Group::signatures).toList());
        assertTrue(groups.stream().allMatch(g->g.members().stream().map(TinyChunkGroups.Definition::owner).distinct().count()==1));
    }
    @Test void tinyMethodsAtEitherEndJoinTheirLogicalNeighbourWithoutChangingLines() {
        String source="class C {\n int a(){return 1;}\n int b(){\n  int x=2;\n  x++;\n  x++;\n  return x;\n }\n int c(){return 3;}\n}";
        var group=groups(source,6000).getFirst();
        assertEquals(3,group.members().size());assertEquals(2,group.source().firstLine());assertEquals(9,group.source().lastLine());
        assertEquals(source.substring(group.source().startOffset(),group.source().endOffset()),group.source().text());
    }
    @Test void independentLargeMethodsRemainSeparateAndGroupsRespectCharacterLimit() {
        String method="int %s(){\n int a=1;\n a++;\n a++;\n return a;\n}\n";
        assertEquals(2,groups("class C {"+method.formatted("a")+method.formatted("b")+"}",6000).size());
        String source="class C { String a(){return \""+"a".repeat(300)+"\";} String b(){return \""+"b".repeat(300)+"\";} }";
        assertEquals(2,groups(source,512).size());
    }
    @Test void documentationAndCrLfRemainOriginalAndMemberOffsetsStayIndependent() {
        String source="class C {\r\n /** one */\r\n int a(){return 1;}\r\n /** two */\r\n int b(){return 2;}\r\n}";
        var group=groups(source,6000).getFirst();
        assertEquals(2,group.members().size());assertEquals(2,group.source().firstLine());assertEquals(5,group.source().lastLine());
        assertEquals("/** one */\r\n int a(){return 1;}\r\n /** two */\r\n int b(){return 2;}",group.source().text());
        assertEquals("/** one */\r\n int a(){return 1;}",group.members().getFirst().source().text());
    }
}
