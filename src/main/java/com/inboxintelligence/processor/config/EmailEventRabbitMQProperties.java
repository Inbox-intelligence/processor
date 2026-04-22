package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.rabbitmq.email-event")
public record EmailEventRabbitMQProperties(
        String exchange,
        String sanitizationQueue,
        String sanitizationRoutingKey,
        String embeddingQueue,
        String embeddingRoutingKey,
        String clusteringQueue,
        String clusteringRoutingKey
) {
}
