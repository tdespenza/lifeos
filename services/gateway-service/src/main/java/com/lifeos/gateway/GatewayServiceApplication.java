package com.lifeos.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the LifeOS public API gateway.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayServiceApplication {

    /**
     * Starts the gateway application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayServiceApplication.class, args);
    }
}
