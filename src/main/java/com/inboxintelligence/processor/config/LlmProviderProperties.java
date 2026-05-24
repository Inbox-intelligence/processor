package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm-provider")
public record LlmProviderProperties(
        String name,
        String ollamaUrl,
        String model
) {
}
