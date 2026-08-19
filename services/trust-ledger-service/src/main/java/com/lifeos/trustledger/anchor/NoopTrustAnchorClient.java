package com.lifeos.trustledger.anchor;

import com.lifeos.trustledger.proof.TrustDocumentProofRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Safe default: no request is reported anchored without an explicit external adapter. */
@Component
@ConditionalOnProperty(value = "trust-ledger.besu.enabled", havingValue = "false", matchIfMissing = true)
public class NoopTrustAnchorClient implements TrustAnchorClient {

    @Override
    public AnchorResult anchor(TrustDocumentProofRequest request) {
        throw new TrustAnchorUnavailableException();
    }
}
