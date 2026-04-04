package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.rabbitmq.email-embedding")
public record EmailEmbeddingQueueProperties(String exchange, String queue, String routingKey) {
}
