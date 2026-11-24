package com.wynk.codecontext.port.contract.source;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class JavaCallSiteAnalyzerTest {
    @Test void findsMethodAndLambdaCallSitesWithExactSourceLines() {
        String source = """
                package fixture;
                class PaymentService {
                    void calculate(Money money) {
                        money.convert(1);
                        Runnable r = () -> money.convert(2);
                    }
                }
                """;
        var result = JavaCallSiteAnalyzer.analyze("PaymentService.java", source, Set.of("convert"));
        assertTrue(result.complete());
        assertEquals(2, result.callSites().size());
        assertTrue(result.callSites().stream().allMatch(c -> "PaymentService".equals(c.className()) && "calculate".equals(c.methodName())));
        assertEquals(4, result.callSites().get(0).startLine());
        assertTrue(result.callSites().get(1).source().contains("money.convert(2)"));
    }

    @Test void malformedJavaIsReportedIncompleteRatherThanAsNoUsage() {
        var result = JavaCallSiteAnalyzer.analyze("Broken.java", "class Broken { void x() { client.fetch( }", Set.of("fetch"));
        assertFalse(result.complete());
        assertTrue(result.callSites().isEmpty());
    }
}
