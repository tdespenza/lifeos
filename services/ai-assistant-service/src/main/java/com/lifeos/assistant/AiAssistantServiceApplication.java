package com.lifeos.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

/** Entry point for the independently deployable, fail-closed AI Assistant foundation. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AiAssistantServiceApplication {

    @Bean
    Clock aiAssistantClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(AiAssistantServiceApplication.class, args);
    }
}
