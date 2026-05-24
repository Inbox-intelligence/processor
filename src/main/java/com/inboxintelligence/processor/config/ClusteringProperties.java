package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "clustering.incremental")
public record ClusteringProperties(
        double minSimilarityThreshold
) {
}
