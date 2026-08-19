package com.lifeos.calendar.config;

import com.lifeos.calendar.outbox.FullJitterRetryPolicy;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Injectable time and retry primitives keep scheduling and relay behavior testable. */
@Configuration
public class CalendarRuntimeConfiguration {

    @Bean
    Clock calendarClock() {
        return Clock.systemUTC();
    }

    @Bean
    RandomGenerator calendarRandomGenerator() {
        return RandomGenerator.getDefault();
    }

    @Bean("calendarOutboxRetryPolicy")
    FullJitterRetryPolicy calendarOutboxRetryPolicy(
            CalendarProperties properties, @Qualifier("calendarRandomGenerator") RandomGenerator randomGenerator) {
        return new FullJitterRetryPolicy(
                properties.getOutbox().getInitialBackoff(), properties.getOutbox().getMaxBackoff(), randomGenerator);
    }

    /** A bounded virtual-thread pool keeps Kafka acknowledgement waits off request/scheduler threads. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService calendarOutboxExecutor(CalendarProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getOutbox().getMaxConcurrentPublishes(),
                Thread.ofVirtual().name("calendar-outbox-", 0).factory());
    }
}
