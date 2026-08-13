package com.lifeos.taskgoal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TaskGoalServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TaskGoalServiceApplication.class, args);
    }
}
