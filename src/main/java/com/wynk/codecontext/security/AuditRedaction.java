package com.wynk.codecontext.security;

import com.wynk.codecontext.port.crossrepo.PromptSafety;

import java.util.regex.Pattern;

/**
 * Redacts credential-shaped text before it is written to audit or application logs.
 *
 * <p>Builds on {@link PromptSafety#redactSecrets(String)} and adds header/token shapes that appear
 * in HTTP and webhook handling rather than repository source.
 */
public final class AuditRedaction {
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?i)(authorization\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern API_KEY_HEADER = Pattern.compile(
            "(?i)(x-api-key\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern HUB_SIGNATURE = Pattern.compile(
            "(?i)(x-hub-signature-256\\s*[:=]\\s*sha256=)([0-9a-f]{16,})");
    private static final Pattern QUERY_TOKEN = Pattern.compile(
            "(?i)([?&](?:token|access_token|api_key|key)=)([^&\\s]+)");

    private AuditRedaction() {}

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String out = PromptSafety.redactSecrets(value);
        out = AUTHORIZATION_HEADER.matcher(out).replaceAll("$1" + PromptSafety.REDACTED);
        out = API_KEY_HEADER.matcher(out).replaceAll("$1" + PromptSafety.REDACTED);
        out = HUB_SIGNATURE.matcher(out).replaceAll("$1" + PromptSafety.REDACTED);
        out = QUERY_TOKEN.matcher(out).replaceAll("$1" + PromptSafety.REDACTED);
        return out;
    }
}
