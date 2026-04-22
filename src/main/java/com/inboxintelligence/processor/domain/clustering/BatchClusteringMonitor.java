package com.inboxintelligence.processor.domain.clustering;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchClusteringMonitor {

    private static final String BATCH_CLUSTERING_KEY = "BATCH_CLUSTERING_IN_PROCESS";

    private final StringRedisTemplate redisTemplate;

    public boolean isActive() {
        String value = redisTemplate.opsForValue().get(BATCH_CLUSTERING_KEY);
        boolean active = Boolean.parseBoolean(value);
        if (active) {
            log.debug("Redis key [{}] is active — batch clustering in progress", BATCH_CLUSTERING_KEY);
        }
        return active;
    }
}
