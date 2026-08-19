package com.lifeos.trustledger.certificate;

import java.time.Instant;
import java.util.UUID;

public record TrustGoalCertificateResponse(
        UUID certificateId,
        UUID goalId,
        long goalVersion,
        Instant completedAt,
        String achievementDigestSha256,
        TrustGoalCertificateState state,
        String transactionHash,
        Long blockNumber,
        Instant updatedAt) {

    public static TrustGoalCertificateResponse from(TrustGoalCertificate certificate) {
        return new TrustGoalCertificateResponse(
                certificate.getCertificateId(),
                certificate.getGoalId(),
                certificate.getGoalVersion(),
                certificate.getCompletedAt(),
                certificate.getAchievementDigestSha256(),
                certificate.getState(),
                certificate.getTransactionHash(),
                certificate.getBlockNumber(),
                certificate.getUpdatedAt());
    }
}
