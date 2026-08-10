package com.lifeos.identity.account;

import java.util.Locale;

/**
 * Applies the one canonical representation used for account email lookup.
 *
 * <p>Email addresses are trimmed and lower-cased with {@link Locale#ROOT}. This is intentionally a
 * small syntactic policy: provider-specific mailbox aliases are not inferred or rewritten.
 */
public final class EmailAddressNormalizer {

    /**
     * Prevents instantiation of this stateless utility.
     */
    private EmailAddressNormalizer() {
    }

    /**
     * Returns the canonical lookup representation of an email address.
     *
     * @param email raw email address from an API boundary
     * @return trimmed, locale-independent lower-case email address
     * @throws IllegalArgumentException when {@code email} is null or blank
     */
    public static String normalize(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email address must not be blank");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
