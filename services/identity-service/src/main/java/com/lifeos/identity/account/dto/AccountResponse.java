package com.lifeos.identity.account.dto;

import com.lifeos.identity.account.UserAccount;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(UUID id, String email, String displayName, Instant createdAt) {

    public static AccountResponse from(UserAccount account) {
        return new AccountResponse(
                account.getId(), account.getEmail(), account.getDisplayName(), account.getCreatedAt());
    }
}
