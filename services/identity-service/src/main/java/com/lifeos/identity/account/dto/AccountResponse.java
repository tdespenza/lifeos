package com.lifeos.identity.account.dto;

import com.lifeos.identity.account.UserAccount;
import java.time.Instant;
import java.util.UUID;

/**
 * Public account representation returned by the identity service.
 *
 * @param id stable account identifier
 * @param email registered email address
 * @param displayName account display name
 * @param createdAt account creation timestamp
 */
public record AccountResponse(UUID id, String email, String displayName, Instant createdAt) {

    /**
     * Maps the persistence entity to the API representation.
     *
     * @param account persisted account
     * @return response DTO containing the account's public fields
     */
    public static AccountResponse from(UserAccount account) {
        return new AccountResponse(
                account.getId(), account.getEmail(), account.getDisplayName(), account.getCreatedAt());
    }
}
