package com.wynk.codecontext.port.contract.source;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class NativeSyntaxTest {
    @Test void expiredDeadlineAbortsNativeParsingAndTheParserCanBeReused() {
        try(var parser=NativeSyntax.Parser.builder().language(NativeSyntax.Language.JAVA).build()) {
            parser.setTimeout(Duration.ofNanos(1));
            assertThrows(IllegalStateException.class,()->parser.parse("class Policy { int refund(){return 1;} }"));
            parser.setTimeout(Duration.ofSeconds(2));
            try(var tree=parser.parse("class Policy { int refund(){return 1;} }")) {
                assertFalse(tree.getRootNode().hasError());
            }
        }
    }
    @Test void interruptionAbortsWithoutClearingTheWorkersInterruptFlag() {
        try(var parser=NativeSyntax.Parser.builder().language(NativeSyntax.Language.PYTHON).build()) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(IllegalStateException.class,()->parser.parse("def refund(amount):\n    return amount\n"));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {Thread.interrupted();}
        }
    }
}
