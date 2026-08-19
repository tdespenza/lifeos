package com.lifeos.taskgoal.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** Ensures a deployment cannot start Task/Goal service with blank datasource configuration. */
class TaskGoalDatasourcePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DatasourcePropertiesConfiguration.class);

    @Test
    void rejectsEveryBlankDatasourceField() {
        assertInvalid("url");
        assertInvalid("username");
        assertInvalid("password");
    }

    @Test
    void failsContextStartupWithTheNamedEnvironmentVariable() {
        assertContextStartupFailsFor("url", "TASK_GOAL_DATASOURCE_URL");
        assertContextStartupFailsFor("username", "TASK_GOAL_DATASOURCE_USERNAME");
        assertContextStartupFailsFor("password", "TASK_GOAL_DATASOURCE_PASSWORD");
    }

    private void assertInvalid(String blankField) {
        TaskGoalDatasourceProperties properties = new TaskGoalDatasourceProperties();
        properties.setUrl(blankField.equals("url") ? " " : "jdbc:postgresql://database/task-goal");
        properties.setUsername(blankField.equals("username") ? " " : "task-goal-service");
        properties.setPassword(blankField.equals("password") ? " " : "test-only-password");

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(properties))
                    .anyMatch(violation -> violation.getPropertyPath().toString().equals(blankField));
        }
    }

    private void assertContextStartupFailsFor(String blankField, String environmentVariable) {
        String url = blankField.equals("url") ? "" : "jdbc:h2:mem:task-goal";
        String username = blankField.equals("username") ? "" : "sa";
        String password = blankField.equals("password") ? "" : "task-goal-test-password";

        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.datasource.username=" + username,
                        "spring.datasource.password=" + password)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNotNull();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(environmentVariable)
                            .hasStackTraceContaining("must be configured and non-blank");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TaskGoalDatasourceProperties.class)
    static class DatasourcePropertiesConfiguration {
    }
}
