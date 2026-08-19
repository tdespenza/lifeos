package com.lifeos.documentvault.config;

import jakarta.validation.constraints.NotBlank;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Selects the object-store boundary. The local implementation is development-only; production
 * must provide a reviewed adapter instead of silently retaining files on a container filesystem.
 */
@ConfigurationProperties(prefix = "document-vault.storage")
@Validated
public class DocumentVaultStorageProperties {

    public enum Mode {
        LOCAL_DEVELOPMENT,
        PRODUCTION_ADAPTER
    }

    private Mode mode = Mode.LOCAL_DEVELOPMENT;

    @NotBlank(message = "localRoot must be configured for the local development object store")
    private String localRoot = "./var/document-vault-objects";

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public String getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(String localRoot) {
        this.localRoot = localRoot;
    }

    public Path localRootPath() {
        return Path.of(localRoot).toAbsolutePath().normalize();
    }
}
