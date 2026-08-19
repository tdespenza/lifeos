package com.lifeos.trustledger.anchor;

import com.lifeos.trustledger.proof.TrustDocumentProofRequest;

/** External anchor boundary; implementations must submit only the digest and minimal metadata. */
public interface TrustAnchorClient {

    AnchorResult anchor(TrustDocumentProofRequest request);

    /** Anchors a digest-only commitment that is not a document proof request. */
    default AnchorResult anchorDigest(String checksumSha256) {
        throw new TrustAnchorUnavailableException();
    }

    record AnchorResult(String transactionHash, long blockNumber) {
    }
}
