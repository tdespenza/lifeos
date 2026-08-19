package com.lifeos.trustledger.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Shared deterministic runtime collaborators for Trust Ledger persistence and consumers. */
@Configuration
@EnableConfigurationProperties(TrustMediaAnchorProperties.class)
public class TrustLedgerRuntimeConfiguration {

    @Bean
    Clock trustLedgerClock() {
        return Clock.systemUTC();
    }
}
