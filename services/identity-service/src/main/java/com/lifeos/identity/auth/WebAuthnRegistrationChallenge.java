package com.lifeos.identity.auth;

import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import java.util.UUID;

/** Single-use server-side state for an authenticated WebAuthn registration ceremony. */
public record WebAuthnRegistrationChallenge(
        UUID accountId, PublicKeyCredentialCreationOptions request) {
}
