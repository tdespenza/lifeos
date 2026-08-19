package com.lifeos.identity.account;

/**
 * Small, explicit password policy for first-party account enrollment.
 *
 * <p>The policy follows the modern length-first approach: it requires enough entropy opportunity
 * without composition rules that train users into predictable substitutions. Control characters
 * are rejected so a password never creates unsafe logs, headers, or terminal behavior if a future
 * defect accidentally handles it as text.
 */
public final class RegistrationPasswordPolicy {

    /** Minimum accepted UTF-16 length for public account passwords. */
    public static final int MINIMUM_LENGTH = 12;

    /** Maximum accepted UTF-16 length, bounding request and Argon2id work. */
    public static final int MAXIMUM_LENGTH = 128;

    private RegistrationPasswordPolicy() {
    }

    /**
     * Rejects an invalid registration password without returning the supplied value.
     *
     * @param password transient raw password
     * @throws InvalidRegistrationPasswordException when the policy is not satisfied
     */
    public static void requireValid(String password) {
        if (password == null || password.length() < MINIMUM_LENGTH || password.length() > MAXIMUM_LENGTH) {
            throw new InvalidRegistrationPasswordException();
        }
        boolean containsNonWhitespace = false;
        for (int offset = 0; offset < password.length();) {
            int codePoint = password.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new InvalidRegistrationPasswordException();
            }
            containsNonWhitespace |= !Character.isWhitespace(codePoint);
            offset += Character.charCount(codePoint);
        }
        if (!containsNonWhitespace) {
            throw new InvalidRegistrationPasswordException();
        }
    }
}
