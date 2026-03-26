package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "email-storage")
public record EmailStorageProperties(
        String provider
) {
}
