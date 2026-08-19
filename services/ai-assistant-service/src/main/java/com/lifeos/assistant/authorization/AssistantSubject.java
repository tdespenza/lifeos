package com.lifeos.assistant.authorization;

import java.util.UUID;
import org.springframework.util.StringUtils;

/** Authenticated subject facts returned by Identity; bearer credentials are never retained. */
public record AssistantSubject(UUID accountId, UUID sessionId, String authenticationMethod, String accessTokenProof) {

    public AssistantSubject {
        if (accountId == null || sessionId == null || !StringUtils.hasText(authenticationMethod)) {
            throw new IllegalArgumentException("Identity subject is incomplete");
        }
        if (accessTokenProof == null || !accessTokenProof.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Identity access token proof is invalid");
        }
    }

    @Override
    public String toString() {
        return "AssistantSubject[accountId=" + accountId + ", sessionId=" + sessionId
                + ", authenticationMethod=" + authenticationMethod + ", accessTokenProof=[redacted]]";
    }
}
