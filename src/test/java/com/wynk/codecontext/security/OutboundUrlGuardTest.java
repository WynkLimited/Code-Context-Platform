package com.wynk.codecontext.security;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OutboundUrlGuardTest {
    @Test void rejectsLoopbackLiteralEvenWhenAllowlisted() throws Exception {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlGuard.requireAllowlistedHttps(
                        "https://127.0.0.1/acme/repo.git",
                        Set.of("127.0.0.1"),
                        host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
        assertTrue(failure.getMessage().toLowerCase().contains("restricted"));
    }

    @Test void allowsRfc1918ForAllowlistedCorporateHost() throws Exception {
        var uri = OutboundUrlGuard.requireAllowlistedHttps(
                "https://git.example.com/acme/repo.git",
                Set.of("git.example.com"),
                host -> new InetAddress[]{InetAddress.getByName("10.0.0.25")});
        assertEquals("git.example.com", uri.getHost());
        assertTrue(OutboundUrlGuard.isNonPublicAddress(InetAddress.getByName("10.0.0.25")));
        assertFalse(OutboundUrlGuard.isRestrictedAddress(InetAddress.getByName("10.0.0.25")));
    }

    @Test void rejectsLinkLocalMetadataAddress() throws Exception {
        assertTrue(OutboundUrlGuard.isRestrictedAddress(InetAddress.getByName("169.254.169.254")));
        var failure = assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlGuard.requireAllowlistedHttps(
                        "https://metadata.example/acme/repo",
                        Set.of("metadata.example"),
                        host -> new InetAddress[]{InetAddress.getByName("169.254.169.254")}));
        assertTrue(failure.getMessage().toLowerCase().contains("restricted"));
    }

    @Test void classifiesUniqueLocalIpv6AsNonPublicButNotAlwaysRestricted() throws Exception {
        InetAddress ula = InetAddress.getByName("fd12:3456:789a::1");
        assertFalse(OutboundUrlGuard.isRestrictedAddress(ula));
        assertTrue(OutboundUrlGuard.isNonPublicAddress(ula));
    }

    @Test void rejectsLocalhostAliasWithoutDns() {
        var failure = assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlGuard.requireAllowlistedHttps(
                        "https://localhost/acme/repo.git",
                        Set.of("localhost"),
                        host -> { throw new AssertionError("DNS must not be consulted for localhost"); }));
        assertTrue(failure.getMessage().toLowerCase().contains("restricted"));
    }

    @Test void rejectsCredentialsPortQueryAndFragment() {
        assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlGuard.requireAllowlistedHttps("https://user:pass@github.com/acme/repo", Set.of("github.com"), host -> new InetAddress[0]));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlGuard.requireAllowlistedHttps("https://github.com:8443/acme/repo", Set.of("github.com"), host -> new InetAddress[0]));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlGuard.requireAllowlistedHttps("https://github.com/acme/repo?token=1", Set.of("github.com"), host -> new InetAddress[0]));
        assertThrows(IllegalArgumentException.class, () ->
                OutboundUrlGuard.requireAllowlistedHttps("http://github.com/acme/repo", Set.of("github.com"), host -> new InetAddress[0]));
    }

    @Test void acceptsPublicAllowlistedHost() throws Exception {
        var uri = OutboundUrlGuard.requireAllowlistedHttps(
                "https://github.com/acme/repo.git",
                Set.of("github.com"),
                host -> new InetAddress[]{InetAddress.getByName("1.1.1.1")});
        assertEquals("github.com", uri.getHost());
        RestrictedNetworkEgress.assertPermitted(InetAddress.getByName("1.1.1.1"));
        assertThrows(IllegalArgumentException.class, () -> RestrictedNetworkEgress.assertPermitted(InetAddress.getByName("127.0.0.1")));
    }
}
