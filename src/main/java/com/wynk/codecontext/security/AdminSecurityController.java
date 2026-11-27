package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin-only security status. Never returns API key material — only team names and key counts
 * so operators can confirm dual-key rotation windows without secret disclosure.
 */
@RestController
@RequestMapping("/api/admin/security")
public class AdminSecurityController {
    private final CodeContextProperties properties;

    public AdminSecurityController(CodeContextProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api-keys")
    public Map<String, Object> apiKeyStatus(Authentication auth) {
        requireAdmin(auth);
        ApiKeyRoster team = ApiKeyRoster.parse(properties.apiKeys());
        ApiKeyRoster admin = ApiKeyRoster.parse(properties.adminApiKeys());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamKeyCounts", team.teamKeyCounts());
        body.put("adminKeyCounts", admin.teamKeyCounts());
        body.put("teamCount", team.teams().size());
        body.put("adminTeamCount", admin.teams().size());
        body.put("rotationHint", "Configure team=current|previous for dual-key overlap; drop previous to revoke.");
        return Map.copyOf(body);
    }

    private static void requireAdmin(Authentication auth) {
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()))) {
            throw new AccessDeniedException("administrator key required");
        }
    }
}
