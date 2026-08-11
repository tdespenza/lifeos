package com.lifeos.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Resolves the address used for keyed audit data without trusting client-supplied forwarding
 * headers from untrusted peers.
 */
@Component
public class ClientAddressResolver {

    private static final int MAX_ADDRESS_LENGTH = 64;

    private final Set<String> trustedProxyAddresses;

    /**
     * Creates a resolver from the exact immediate proxy allow-list.
     *
     * @param properties authentication properties
     */
    public ClientAddressResolver(IdentityAuthProperties properties) {
        this.trustedProxyAddresses = Set.copyOf(properties.getTrustedProxyAddresses());
    }

    /**
     * Returns the direct peer address unless that peer is trusted to provide X-Forwarded-For.
     *
     * @param request current servlet request
     * @return bounded client address for keyed fingerprinting
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (remoteAddress == null || !trustedProxyAddresses.contains(remoteAddress)) {
            return remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null) {
            return remoteAddress;
        }
        String clientAddress = forwardedFor.split(",", 2)[0].trim();
        return clientAddress.length() <= MAX_ADDRESS_LENGTH && !clientAddress.isBlank()
                ? clientAddress : remoteAddress;
    }
}
