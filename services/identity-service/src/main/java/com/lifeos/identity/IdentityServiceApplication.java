package com.lifeos.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the LifeOS identity service.
 */
@SpringBootApplication
public class IdentityServiceApplication {

    /**
     * Creates the application entry point.
     */
    public IdentityServiceApplication() {
    }

    /**
     * Starts the Spring application context and embedded web server.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
