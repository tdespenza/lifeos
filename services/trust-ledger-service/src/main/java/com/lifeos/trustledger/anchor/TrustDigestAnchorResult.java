package com.lifeos.trustledger.anchor;

import java.time.Instant;
import java.util.UUID;

public record TrustDigestAnchorResult(
        UUID requestId,
        String subjectType,
        UUID subjectId,
        long subjectVersion,
        String digestSha256,
        TrustDigestAnchorState state,
        String transactionHash,
        Long blockNumber,
        Instant updatedAt) {

    public static TrustDigestAnchorResult from(TrustDigestAnchorRequest request) {
        return new TrustDigestAnchorResult(
                request.getRequestId(),
                request.getSubjectType(),
                request.getSubjectId(),
                request.getSubjectVersion(),
                request.getDigestSha256(),
                request.getState(),
                request.getTransactionHash(),
                request.getBlockNumber(),
                request.getUpdatedAt());
    }
}
