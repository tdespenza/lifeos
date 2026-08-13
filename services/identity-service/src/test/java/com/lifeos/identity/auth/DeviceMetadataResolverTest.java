package com.lifeos.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeviceMetadataResolverTest {

    @Test
    void derivesOnlyCoarseBoundedClassifications() {
        DeviceMetadata metadata = DeviceMetadataResolver.fromUserAgent(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36");

        assertThat(metadata.platform()).isEqualTo("macos");
        assertThat(metadata.browserFamily()).isEqualTo("chrome");
        assertThat(metadata.coarseLocation()).isEqualTo("unknown");
        assertThat(metadata.label()).isEqualTo("chrome on macos");
    }

    @Test
    void malformedOrMissingUserAgentUsesUnknownValue() {
        assertThat(DeviceMetadataResolver.fromUserAgent(null)).isEqualTo(DeviceMetadata.unknown());
        assertThat(DeviceMetadataResolver.fromUserAgent("\u0000\u0001")).isEqualTo(DeviceMetadata.unknown());
    }

    @Test
    void metadataValuesAreNormalizedAndBounded() {
        DeviceMetadata metadata = new DeviceMetadata("  Windows! ", "Browser/with?input", "A".repeat(100));

        assertThat(metadata.platform()).isEqualTo("windows");
        assertThat(metadata.browserFamily()).isEqualTo("browserwithinput");
        assertThat(metadata.coarseLocation()).hasSize(64);
    }
}
