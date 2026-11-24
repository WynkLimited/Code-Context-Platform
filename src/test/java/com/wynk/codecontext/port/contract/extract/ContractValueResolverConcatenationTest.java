package com.wynk.codecontext.port.contract.extract;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ContractValueResolverConcatenationTest {

    @Test
    void resolvesConstantPathComposition() {
        String source = """
                static final String BASE = "/campaign";
                static final String ID = "/{id}";
                @Path(BASE + ID)
                """;
        Map<String, String> constants = ContractValueResolver.constants(source);
        assertEquals("/campaign/{id}", ContractValueResolver.resolve("BASE + ID", constants, Map.of()));
    }

    @Test
    void refusesDynamicConcatenation() {
        assertNull(ContractValueResolver.resolve("\"/campaigns/\" + id", Map.of(), Map.of()));
    }
}
