package com.wynk.codecontext.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * SSRF controls for operator- or repository-supplied outbound URLs.
 *
 * <p>Host allowlists alone are insufficient: an allowlisted name can resolve to loopback,
 * link-local, RFC1918, unique-local IPv6, or cloud metadata addresses, and HTTP clients may
 * follow redirects to those targets. Callers must also disable redirects where the client
 * supports it (JGit {@code http.followRedirects=false}, Resolver {@code aether.connector.http.followRedirects=false})
 * and retain cluster egress controls for DNS-to-connect races.
 */
public final class OutboundUrlGuard {
    @FunctionalInterface
    public interface NameResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final NameResolver DEFAULT_RESOLVER = InetAddress::getAllByName;

    private OutboundUrlGuard() {}

    /** Rejects non-HTTPS remote URLs and hosts outside {@code allowedHosts} (case-insensitive). */
    public static URI requireAllowlistedHttps(String value, Collection<String> allowedHosts) {
        return requireAllowlistedHttps(value, allowedHosts, DEFAULT_RESOLVER);
    }

    public static URI requireAllowlistedHttps(String value, Collection<String> allowedHosts, NameResolver resolver) {
        Objects.requireNonNull(allowedHosts, "allowedHosts");
        Objects.requireNonNull(resolver, "resolver");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("invalid or disallowed URL", invalid);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("URL scheme/credentials/port/query/fragment is not allowed");
        }
        boolean allowed = allowedHosts.stream()
                .map(String::strip)
                .filter(h -> !h.isBlank())
                .anyMatch(h -> h.equalsIgnoreCase(uri.getHost()));
        if (!allowed) {
            throw new IllegalArgumentException("URL host is not allowlisted");
        }
        rejectPrivateResolvedAddresses(uri.getHost(), resolver);
        return uri;
    }

    /** Resolves {@code host} and rejects any private, loopback, link-local, or metadata address. */
    public static void rejectPrivateResolvedAddresses(String host) {
        rejectPrivateResolvedAddresses(host, DEFAULT_RESOLVER);
    }

    public static void rejectPrivateResolvedAddresses(String host, NameResolver resolver) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("URL host is required");
        }
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".localhost") || normalized.endsWith(".local")) {
            throw new IllegalArgumentException("URL host resolves to a restricted address");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException unknown) {
            throw new IllegalArgumentException("URL host cannot be resolved", unknown);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("URL host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (isRestrictedAddress(address)) {
                throw new IllegalArgumentException("URL host resolves to a restricted address");
            }
        }
    }

    /**
     * Addresses that must never be contacted even when the hostname is allowlisted.
     *
     * <p>Blocks loopback, any-local, link-local (including cloud metadata {@code 169.254.169.254}),
     * multicast and IPv4 {@code 0.0.0.0/8}. RFC1918 / unique-local addresses are allowed so
     * allowlisted GitHub Enterprise or Maven hosts on a private corporate network keep working;
     * cluster NetworkPolicy remains the control for broader private egress.
     */
    public static boolean isRestrictedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xff;
            int b1 = raw[1] & 0xff;
            // 169.254.0.0/16 link-local (also covers 169.254.169.254 metadata).
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            // 0.0.0.0/8
            if (b0 == 0) {
                return true;
            }
        }
        if (raw.length == 16) {
            // IPv4-mapped restricted addresses
            boolean v4Mapped = raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 0
                    && raw[4] == 0 && raw[5] == 0 && raw[6] == 0 && raw[7] == 0
                    && raw[8] == 0 && raw[9] == 0 && raw[10] == (byte) 0xff && raw[11] == (byte) 0xff;
            if (v4Mapped) {
                try {
                    return isRestrictedAddress(InetAddress.getByAddress(Arrays.copyOfRange(raw, 12, 16)));
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Broader non-public classification for egress-policy assertions (includes RFC1918 / ULA). */
    public static boolean isNonPublicAddress(InetAddress address) {
        if (isRestrictedAddress(address) || address == null) {
            return true;
        }
        if (address.isSiteLocalAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int b0 = raw[0] & 0xff;
            int b1 = raw[1] & 0xff;
            // 100.64.0.0/10 carrier-grade NAT
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true;
            }
        }
        if (raw.length == 16 && (raw[0] & 0xfe) == 0xfc) {
            return true; // fc00::/7 unique local
        }
        return false;
    }

    public static String summarizeRestricted(InetAddress[] addresses) {
        if (addresses == null) {
            return "";
        }
        return Arrays.stream(addresses)
                .filter(OutboundUrlGuard::isRestrictedAddress)
                .map(InetAddress::getHostAddress)
                .collect(Collectors.joining(","));
    }
}
