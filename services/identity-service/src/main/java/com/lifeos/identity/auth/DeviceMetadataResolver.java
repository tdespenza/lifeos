package com.lifeos.identity.auth;

import java.util.Locale;

/**
 * Derives bounded device classifications from an untrusted user-agent header.
 *
 * <p>The parser is intentionally conservative. It is a presentation aid, not an identity signal,
 * and unknown or malformed input produces the same safe fallback.
 */
public final class DeviceMetadataResolver {

    private static final int MAX_USER_AGENT_LENGTH = 512;

    private DeviceMetadataResolver() {
    }

    /**
     * Resolves coarse metadata without retaining the source header.
     *
     * @param userAgent untrusted HTTP user-agent value
     * @return bounded device metadata
     */
    public static DeviceMetadata fromUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return DeviceMetadata.unknown();
        }
        String value = userAgent.substring(0, Math.min(MAX_USER_AGENT_LENGTH, userAgent.length()))
                .toLowerCase(Locale.ROOT);
        String platform = platform(value);
        String browser = browser(value);
        return new DeviceMetadata(platform, browser, "unknown");
    }

    private static String platform(String userAgent) {
        if (userAgent.contains("android")) {
            return "android";
        }
        if (userAgent.contains("iphone") || userAgent.contains("ipad") || userAgent.contains("ios")) {
            return "ios";
        }
        if (userAgent.contains("windows")) {
            return "windows";
        }
        if (userAgent.contains("macintosh") || userAgent.contains("mac os")) {
            return "macos";
        }
        if (userAgent.contains("linux") || userAgent.contains("x11")) {
            return "linux";
        }
        return "unknown";
    }

    private static String browser(String userAgent) {
        if (userAgent.contains("edg/")) {
            return "edge";
        }
        if (userAgent.contains("opr/") || userAgent.contains("opera")) {
            return "opera";
        }
        if (userAgent.contains("samsungbrowser")) {
            return "samsung internet";
        }
        if (userAgent.contains("firefox/") || userAgent.contains("fxios/")) {
            return "firefox";
        }
        if (userAgent.contains("chrome/") || userAgent.contains("crios/")) {
            return "chrome";
        }
        if (userAgent.contains("safari/") && !userAgent.contains("chrome/")) {
            return "safari";
        }
        return "unknown";
    }
}
