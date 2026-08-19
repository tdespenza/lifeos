package com.lifeos.media.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Injectable clock keeps expiry, idempotency, and adapter behavior deterministic in tests. */
@Configuration
public class MediaRuntimeConfiguration {

    @Bean
    Clock mediaClock() {
        return Clock.systemUTC();
    }
}
