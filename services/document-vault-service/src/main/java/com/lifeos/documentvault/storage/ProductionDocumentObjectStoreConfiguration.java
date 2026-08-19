package com.lifeos.documentvault.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Prevents production configuration from accidentally using a container-local filesystem. */
@Configuration
@ConditionalOnProperty(name = "document-vault.storage.mode", havingValue = "PRODUCTION_ADAPTER")
public class ProductionDocumentObjectStoreConfiguration {

    @Bean
    public DocumentObjectStore productionDocumentObjectStore() {
        throw new IllegalStateException(
                "document-vault.storage.mode=PRODUCTION_ADAPTER requires a reviewed external DocumentObjectStore bean");
    }
}
