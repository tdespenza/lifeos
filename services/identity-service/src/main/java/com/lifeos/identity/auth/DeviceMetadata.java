package com.lifeos.identity.auth;

import java.util.Locale;

/**
 * Coarse, client-safe device information retained with a session.
 *
 * <p>This type deliberately has no raw user-agent, IP address, or fingerprint fields. Values are
 * normalized and bounded before they cross the persistence boundary.
 *
 * @param platform coarse operating-system family
 * @param browserFamily coarse browser family
 * @param coarseLocation coarse location label, or {@code unknown} when no trusted geolocation is
 *     available
 */
public record DeviceMetadata(String platform, String browserFamily, String coarseLocation) {

    private static final String UNKNOWN = "unknown";

    /**
     * Normalizes externally derived labels so the persistence and response contract stays bounded.
     */
    public DeviceMetadata {
        platform = normalize(platform, 32);
        browserFamily = normalize(browserFamily, 32);
        coarseLocation = normalize(coarseLocation, 64);
    }

    /**
     * Returns an intentionally uninformative value for legacy sessions or non-browser clients.
     *
     * @return unknown device metadata
     */
    public static DeviceMetadata unknown() {
        return new DeviceMetadata(UNKNOWN, UNKNOWN, UNKNOWN);
    }

    /**
     * Returns a bounded display label derived only from coarse classifications.
     *
     * @return safe device label
     */
    public String label() {
        if (UNKNOWN.equals(browserFamily) && UNKNOWN.equals(platform)) {
            return "Unknown device";
        }
        if (UNKNOWN.equals(browserFamily)) {
            return platform;
        }
        if (UNKNOWN.equals(platform)) {
            return browserFamily;
        }
        return browserFamily + " on " + platform;
    }

    /**
     * Indicates whether no useful client classification was available.
     *
     * @return {@code true} for the all-unknown value
     */
    public boolean isUnknown() {
        return UNKNOWN.equals(platform) && UNKNOWN.equals(browserFamily) && UNKNOWN.equals(coarseLocation);
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String sanitized = value.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ._-]", "");
        if (sanitized.isBlank()) {
            return UNKNOWN;
        }
        return sanitized.substring(0, Math.min(maxLength, sanitized.length()));
    }
}
