package com.lifeos.trustledger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point for bounded, privacy-preserving proof generation and verification. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TrustLedgerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrustLedgerServiceApplication.class, args);
    }
}
