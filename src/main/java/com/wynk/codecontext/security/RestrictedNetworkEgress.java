package com.wynk.codecontext.security;

import java.net.InetAddress;

/**
 * Application-level hook mirroring cluster NetworkPolicy private-address denial.
 *
 * <p>Kubernetes egress policy still owns hard enforcement. This helper rejects the same address
 * classes before an outbound client is invoked so unit tests can assert the policy without a
 * cluster.
 */
public final class RestrictedNetworkEgress {
    private RestrictedNetworkEgress() {}

    public static void assertPermitted(InetAddress address) {
        if (OutboundUrlGuard.isRestrictedAddress(address)) {
            throw new IllegalArgumentException("egress to restricted address is not permitted");
        }
    }

    public static void assertPermittedHost(String host) {
        OutboundUrlGuard.rejectPrivateResolvedAddresses(host);
    }
}
