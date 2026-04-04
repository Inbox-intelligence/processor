package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spring.rabbitmq.email-processing")
public record EmailProcessingQueueProperties(String exchange, String queue, String routingKey) {
}
