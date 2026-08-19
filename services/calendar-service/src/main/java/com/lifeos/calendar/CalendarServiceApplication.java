package com.lifeos.calendar;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Entry point for the independently deployable Calendar and Scheduling service. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class CalendarServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalendarServiceApplication.class, args);
    }
}
