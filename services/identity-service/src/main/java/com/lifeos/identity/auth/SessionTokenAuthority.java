package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;

/**
 * Shared session/token boundary used by password, OIDC, and passkey authentication flows.
 */
public interface SessionTokenAuthority {

    /**
     * Creates the durable session and versioned access/refresh token result for an authenticated
     * account.
     *
     * @param account active authenticated account
     * @return session/token result
     * @throws SessionCapacityExceededException when the account cannot safely create another session
     */
    LoginResponse createSession(UserAccount account);

    /**
     * Creates a session for an already verified non-password authentication method.
     *
     * <p>The default preserves the original contract for test doubles and future callers. The
     * production implementation applies the method-specific revalidation policy.
     *
     * @param account active authenticated account
     * @param authenticationMethod verified authentication method
     * @return session/token result
     */
    default LoginResponse createSession(
            UserAccount account, SessionAuthenticationMethod authenticationMethod) {
        return createSession(account);
    }

    /**
     * Creates a session while retaining only safe, coarse device metadata.
     *
     * <p>The default preserves compatibility with authentication test doubles and callers that do
     * not have a browser request, while the production authority persists the metadata.
     *
     * @param account active authenticated account
     * @param authenticationMethod verified authentication method
     * @param deviceMetadata bounded device classification
     * @return session/token result
     */
    default LoginResponse createSession(
            UserAccount account,
            SessionAuthenticationMethod authenticationMethod,
            DeviceMetadata deviceMetadata) {
        return createSession(account, authenticationMethod);
    }
}
