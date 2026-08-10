package com.lifeos.identity.auth;

import com.lifeos.identity.account.UserAccount;

/**
 * Shared session/token boundary used by password, OIDC, and passkey authentication flows.
 */
public interface SessionTokenAuthority {

    /**
     * Creates the durable session and versioned token result for an authenticated account.
     *
     * @param account active authenticated account
     * @return session/token result
     * @throws SessionCapacityExceededException when the account cannot safely create another session
     */
    LoginResponse createSession(UserAccount account);
}
