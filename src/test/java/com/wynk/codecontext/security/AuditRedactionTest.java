package com.wynk.codecontext.security;

import com.wynk.codecontext.port.crossrepo.PromptSafety;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditRedactionTest {
    @Test void redactsApiKeyAndAuthorizationHeaders() {
        String raw = "X-API-Key: team-super-secret-value Authorization: Bearer abcdefghijklmnop1234";
        String redacted = AuditRedaction.redact(raw);
        assertFalse(redacted.contains("team-super-secret-value"));
        assertFalse(redacted.contains("abcdefghijklmnop1234"));
        assertTrue(redacted.contains(PromptSafety.REDACTED));
    }

    @Test void redactsWebhookSignaturesAndQueryTokens() {
        String raw = "X-Hub-Signature-256: sha256=0123456789abcdef0123456789abcdef failed url=https://example/?access_token=ghp_abcdefghijklmnopqrstuv";
        String redacted = AuditRedaction.redact(raw);
        assertFalse(redacted.contains("0123456789abcdef0123456789abcdef"));
        assertFalse(redacted.contains("ghp_abcdefghijklmnopqrstuv"));
        assertTrue(redacted.contains(PromptSafety.REDACTED));
    }

    @Test void redactsPasswordAssignmentShapesFromPromptSafety() {
        String raw = "clone failed password=hunter2-extra api_key=abcd1234efgh";
        String redacted = AuditRedaction.redact(raw);
        assertFalse(redacted.contains("hunter2-extra"));
        assertFalse(redacted.contains("abcd1234efgh"));
    }

    @Test void leavesNonSecretOperationalIdsIntact() {
        String raw = "github.webhook.accepted deliveryId=11111111-2222-3333-4444-555555555555 repository=acme/payments";
        assertEquals(raw, AuditRedaction.redact(raw));
    }
}
