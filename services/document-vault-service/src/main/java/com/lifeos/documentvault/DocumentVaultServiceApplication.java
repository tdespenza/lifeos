package com.lifeos.documentvault;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point for the independently deployable owner-scoped Document Vault service. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DocumentVaultServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocumentVaultServiceApplication.class, args);
    }
}
