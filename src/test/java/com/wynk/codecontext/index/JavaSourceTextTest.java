package com.wynk.codecontext.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaSourceTextTest {
    @Test void preservesInlineMethodsWithoutImportingTheNeighbour() {
        String source="class C { int first(){return 1;} int second(){return 2;} }";
        var unit=new JavaParser().parse(source).getResult().orElseThrow();
        var method=unit.findAll(MethodDeclaration.class).getFirst();
        var text=new JavaSourceText(source).definition(method);
        assertEquals("int first(){return 1;}",text.text());
        assertEquals(1,text.firstLine());assertEquals(1,text.lastLine());
    }
    @Test void preservesDocumentationFormattingAndActualMethodLines() {
        String source="class C {\n  /** Meaningful documentation. */\n  int value() {\n    return 1; // retain this comment\n  }\n}\n";
        var method=new JavaParser().parse(source).getResult().orElseThrow().findAll(MethodDeclaration.class).getFirst();
        var text=new JavaSourceText(source).definition(method);
        assertEquals(2,text.firstLine());assertEquals(5,text.lastLine());
        assertEquals("/** Meaningful documentation. */\n  int value() {\n    return 1; // retain this comment\n  }",text.text());
    }
    @Test void classHeaderIncludesAnnotationsAndStateButNotTheFirstMethod() {
        String source="package app;\n/** Class documentation. */\n@Deprecated\nclass C {\n  int state=7;\n  /** Method documentation. */\n  int value(){return state;}\n}\n";
        var type=new JavaParser().parse(source).getResult().orElseThrow().findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        var text=new JavaSourceText(source).classHeader(type);
        assertEquals(2,text.firstLine());assertEquals(5,text.lastLine());
        assertTrue(text.text().contains("@Deprecated"));assertTrue(text.text().contains("int state=7;"));
        assertFalse(text.text().contains("Method documentation"));assertFalse(text.text().contains("value()"));
    }
    @Test void preservesCrLfSourceRanges() {
        String source="class C {\r\n  int value(){\r\n    return 1;\r\n  }\r\n}\r\n";
        var unit=new JavaParser().parse(source).getResult().orElseThrow();
        var text=new JavaSourceText(source).definition(unit.findFirst(MethodDeclaration.class).orElseThrow());
        assertEquals("int value(){\r\n    return 1;\r\n  }",text.text());
        assertEquals(2,text.firstLine());assertEquals(4,text.lastLine());
    }
    @Test void refusesToInventCoordinatesWhenTheParserOmitsRanges() {
        String source="class C { int value(){return 1;} }";
        var unit=new JavaParser(new ParserConfiguration().setStoreTokens(false)).parse(source).getResult().orElseThrow();
        assertThrows(IllegalArgumentException.class,()->new JavaSourceText(source).definition(unit.findFirst(MethodDeclaration.class).orElseThrow()));
    }
    @Test void namesTheEnclosingTypeForOtherwiseIdenticalMethodChunks() {
        String source="package app; class A { class Inner { int value(){return 1;} } } class B { int value(){return 1;} }";
        var methods=new JavaParser().parse(source).getResult().orElseThrow().findAll(MethodDeclaration.class);
        assertEquals("app.A.Inner",JavaSourceText.ownerOf(methods.getFirst()));
        assertEquals("app.B",JavaSourceText.ownerOf(methods.getLast()));
    }
}
