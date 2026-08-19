package com.lifeos.documentvault.config;

import java.time.Clock;
import com.lifeos.documentvault.proof.DocumentProofOutboxRetryPolicy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Small explicit runtime dependencies that are replaced in deterministic storage tests. */
@Configuration
public class DocumentVaultRuntimeConfiguration {

    @Bean
    public Clock documentVaultClock() {
        return Clock.systemUTC();
    }

    @Bean
    public DocumentProofOutboxRetryPolicy documentProofOutboxRetryPolicy(DocumentProofOutboxProperties properties) {
        return new DocumentProofOutboxRetryPolicy(properties.getInitialBackoff(), properties.getMaxBackoff());
    }

    @Bean(destroyMethod = "shutdown")
    public ExecutorService documentProofOutboxExecutor(DocumentProofOutboxProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getMaxConcurrentPublishes(),
                Thread.ofVirtual().name("document-proof-outbox-", 0).factory());
    }
}
