package com.lifeos.documentvault.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Validates the Document Vault database contract before its connection pool is created. */
@ConfigurationProperties(prefix = "spring.datasource")
@Validated
public class DocumentVaultDatasourceProperties {

    @NotBlank(message = "spring.datasource.url (DOCUMENT_VAULT_DATASOURCE_URL) must be configured")
    private String url;

    @NotBlank(message = "spring.datasource.username (DOCUMENT_VAULT_DATASOURCE_USERNAME) must be configured")
    private String username;

    @NotBlank(message = "spring.datasource.password (DOCUMENT_VAULT_DATASOURCE_PASSWORD) must be configured")
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
