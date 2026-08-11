package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Verifies that forwarded client addresses are accepted only from configured immediate proxies.
 */
class ClientAddressResolverTest {

    @Test
    void ignoresForwardedHeaderFromUntrustedPeer() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.setTrustedProxyAddresses(Set.of("10.0.0.10"));
        MockHttpServletRequest request = request("192.0.2.10", "198.51.100.10");

        assertThat(new ClientAddressResolver(properties).resolve(request)).isEqualTo("192.0.2.10");
    }

    @Test
    void usesFirstForwardedAddressFromTrustedProxy() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.setTrustedProxyAddresses(Set.of("10.0.0.10"));
        MockHttpServletRequest request = request("10.0.0.10", "198.51.100.10, 10.0.0.11");

        assertThat(new ClientAddressResolver(properties).resolve(request)).isEqualTo("198.51.100.10");
    }

    @Test
    void ignoresOversizedForwardedAddress() {
        IdentityAuthProperties properties = new IdentityAuthProperties();
        properties.setTrustedProxyAddresses(Set.of("10.0.0.10"));
        MockHttpServletRequest request = request("10.0.0.10", "a".repeat(65));

        assertThat(new ClientAddressResolver(properties).resolve(request)).isEqualTo("10.0.0.10");
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
