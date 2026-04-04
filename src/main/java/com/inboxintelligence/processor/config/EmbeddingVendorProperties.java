package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "embedding-vendor")
public record EmbeddingVendorProperties(
        String provider,
        String ollamaUrl,
        String model,
        Integer numCtx,
        Integer maxChars
) {
}
