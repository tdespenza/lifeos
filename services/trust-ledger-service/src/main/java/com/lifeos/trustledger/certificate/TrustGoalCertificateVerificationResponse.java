package com.lifeos.trustledger.certificate;

public record TrustGoalCertificateVerificationResponse(boolean valid, String result) {

    public static TrustGoalCertificateVerificationResponse validResult() {
        return new TrustGoalCertificateVerificationResponse(true, "VALID");
    }

    public static TrustGoalCertificateVerificationResponse invalidResult() {
        return new TrustGoalCertificateVerificationResponse(false, "INVALID");
    }

    public static TrustGoalCertificateVerificationResponse indeterminateResult() {
        return new TrustGoalCertificateVerificationResponse(false, "INDETERMINATE_EXTERNAL_ANCHOR");
    }
}
