package com.lifeos.identity.auth;

/**
 * Minimal verified identity claims required by the account-linking policy.
 *
 * @param subject provider subject
 * @param email provider email claim
 * @param displayName verified display name, when supplied
 */
public record OidcIdentity(String subject, String email, String displayName) {
}
