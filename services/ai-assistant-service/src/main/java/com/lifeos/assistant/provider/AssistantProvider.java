package com.lifeos.assistant.provider;

/** Provider SPI; implementations must accept only the pre-redacted bounded request object. */
public interface AssistantProvider {

    boolean isConfigured();

    String providerId();

    String modelName();

    AssistantProviderResponse generate(AssistantProviderRequest request);
}
