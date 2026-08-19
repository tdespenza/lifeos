package com.lifeos.assistant.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validates deployment-owned database secrets before a JDBC pool can be created. */
@ConfigurationProperties(prefix = "spring.datasource")
@Validated
public class AssistantDatasourceProperties {

    @NotBlank(message = "spring.datasource.url (AI_ASSISTANT_DATASOURCE_URL) must be configured and non-blank")
    private String url;

    @NotBlank(message = "spring.datasource.username (AI_ASSISTANT_DATASOURCE_USERNAME) must be configured and non-blank")
    private String username;

    @NotBlank(message = "spring.datasource.password (AI_ASSISTANT_DATASOURCE_PASSWORD) must be configured and non-blank")
    private String password;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
