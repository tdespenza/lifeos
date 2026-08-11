package com.lifeos.identity.auth;

import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import java.io.IOException;

/**
 * Parses a client WebAuthn assertion into the protocol library's typed response.
 */
@FunctionalInterface
public interface WebAuthnAssertionParser {

    /**
     * Parses one JSON-encoded browser assertion.
     *
     * @param json assertion JSON from the client
     * @return typed assertion
     * @throws IOException when the payload is malformed
     */
    PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs> parse(
            String json) throws IOException;
}
