package com.lifeos.notification.delivery;

/** Safe provider result metadata used by the durable retry coordinator. */
public record ProviderDeliveryResult(
        ProviderDeliveryOutcome outcome, String reasonCode, String providerMessageId, boolean disableEndpoint) {

    public ProviderDeliveryResult {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        if (reasonCode == null || !reasonCode.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("reasonCode must be a bounded safe code");
        }
        if (providerMessageId != null && (providerMessageId.isBlank() || providerMessageId.length() > 255)) {
            throw new IllegalArgumentException("providerMessageId is blank or too long");
        }
        if (disableEndpoint && outcome != ProviderDeliveryOutcome.PERMANENT_FAILURE) {
            throw new IllegalArgumentException("only permanent provider failures may disable an endpoint");
        }
    }

    public static ProviderDeliveryResult delivered(String providerMessageId) {
        return new ProviderDeliveryResult(ProviderDeliveryOutcome.DELIVERED, "DELIVERED", providerMessageId, false);
    }

    public static ProviderDeliveryResult transientFailure(String reasonCode) {
        return new ProviderDeliveryResult(ProviderDeliveryOutcome.TRANSIENT_FAILURE, reasonCode, null, false);
    }

    public static ProviderDeliveryResult permanentFailure(String reasonCode, boolean disableEndpoint) {
        return new ProviderDeliveryResult(ProviderDeliveryOutcome.PERMANENT_FAILURE, reasonCode, null, disableEndpoint);
    }

    public static ProviderDeliveryResult skipped(String reasonCode) {
        return new ProviderDeliveryResult(ProviderDeliveryOutcome.SKIPPED, reasonCode, null, false);
    }
}
