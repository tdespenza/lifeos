package com.lifeos.taskgoal.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Validates the database contract before Task/Goal service creates a connection pool.
 *
 * <p>The application's deployment configuration maps these values from the named Task/Goal
 * datasource environment variables. Keeping this separate from Spring Boot's own datasource
 * properties gives operators a clear configuration failure for blank values, instead of relying
 * on a driver-specific connection-pool error.
 */
@ConfigurationProperties(prefix = "spring.datasource")
@Validated
public class TaskGoalDatasourceProperties {

    @NotBlank(message = "spring.datasource.url (TASK_GOAL_DATASOURCE_URL) must be configured and non-blank")
    private String url;

    @NotBlank(message = "spring.datasource.username (TASK_GOAL_DATASOURCE_USERNAME) must be configured and non-blank")
    private String username;

    @NotBlank(message = "spring.datasource.password (TASK_GOAL_DATASOURCE_PASSWORD) must be configured and non-blank")
    private String password;

    /**
     * Returns the configured JDBC URL.
     *
     * @return non-blank JDBC URL
     */
    public String getUrl() {
        return url;
    }

    /**
     * Sets the JDBC URL.
     *
     * @param url non-blank JDBC URL
     */
    public void setUrl(String url) {
        this.url = url;
    }

    /**
     * Returns the configured database user name.
     *
     * @return non-blank database user name
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the database user name.
     *
     * @param username non-blank database user name
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the configured database password.
     *
     * @return non-blank database password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the database password.
     *
     * @param password non-blank database password
     */
    public void setPassword(String password) {
        this.password = password;
    }
}
