package com.lifeos.trustledger.anchor;

public record TrustDocumentProofVerificationResponse(boolean valid, String result) {

    public static TrustDocumentProofVerificationResponse validResult() {
        return new TrustDocumentProofVerificationResponse(true, "VALID");
    }

    public static TrustDocumentProofVerificationResponse invalidResult() {
        return new TrustDocumentProofVerificationResponse(false, "INVALID");
    }

    public static TrustDocumentProofVerificationResponse indeterminateResult() {
        return new TrustDocumentProofVerificationResponse(false, "INDETERMINATE_EXTERNAL_ANCHOR");
    }
}
