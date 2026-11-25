package com.wynk.codecontext.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyRosterTest {
    @Test void parsesSingleAndDualKeysWithoutExposingSecretsInToString() {
        var roster = ApiKeyRoster.parse("orders=current-secret|previous-secret,billing=solo-secret");
        assertEquals("orders", roster.keyToTeam().get("current-secret"));
        assertEquals("orders", roster.keyToTeam().get("previous-secret"));
        assertEquals("billing", roster.keyToTeam().get("solo-secret"));
        assertEquals(3, roster.keyToTeam().size());
        String rendered = roster.toString();
        assertTrue(rendered.contains("orders"));
        assertFalse(rendered.contains("current-secret"));
        assertFalse(rendered.contains("previous-secret"));
        assertFalse(rendered.contains("solo-secret"));
    }

    @Test void firstKeyWinsWhenTheSameSecretIsReusedAcrossTeams() {
        var roster = ApiKeyRoster.parse("alpha=shared-secret,beta=shared-secret");
        assertEquals("alpha", roster.keyToTeam().get("shared-secret"));
    }

    @Test void productionStrengthRejectsShortOrDefaultKeys() {
        assertThrows(IllegalStateException.class, () -> ApiKeyRoster.requireProductionStrength("team=local-dev-token", "TEAM_API_KEYS"));
        assertThrows(IllegalStateException.class, () -> ApiKeyRoster.requireProductionStrength("team=short|also-short", "TEAM_API_KEYS"));
        assertDoesNotThrow(() -> ApiKeyRoster.requireProductionStrength(
                "team=" + "n".repeat(32) + "|" + "o".repeat(32), "TEAM_API_KEYS"));
    }
}
