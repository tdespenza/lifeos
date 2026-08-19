package com.lifeos.identity.account;

/** Result of a durable public registration command. */
public record AccountRegistrationResult(UserAccount account, boolean replayed) {
}
