package com.lifeos.trustledger.anchor;

import com.lifeos.trustledger.proof.TrustDocumentProofRequest;
import com.lifeos.trustledger.proof.TrustDocumentProofState;
import java.time.Instant;
import java.util.UUID;

public record TrustAnchorResult(
        UUID requestId,
        TrustDocumentProofState state,
        String transactionHash,
        Long blockNumber,
        Instant updatedAt) {

    public static TrustAnchorResult from(TrustDocumentProofRequest request) {
        return new TrustAnchorResult(
                request.getRequestId(),
                request.getState(),
                request.getTransactionHash(),
                request.getBlockNumber(),
                request.getUpdatedAt());
    }
}
