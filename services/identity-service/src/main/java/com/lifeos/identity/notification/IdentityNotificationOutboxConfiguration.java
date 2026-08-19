package com.lifeos.identity.notification;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bounded virtual-thread executor for recovery notification publication. */
@Configuration
public class IdentityNotificationOutboxConfiguration {

    @Bean(name = "identityNotificationOutboxExecutor", destroyMethod = "close")
    public ExecutorService identityNotificationOutboxExecutor(
            IdentityRecoveryNotificationProperties properties) {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("identity-recovery-notification-", 0).factory());
    }
}
