package com.inboxintelligence.processor.domain.clustering;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BatchClusteringLock {

    private final StringRedisTemplate redisTemplate;
    @Value("${redis-lock.key-prefix}")
    private String keyPrefix;

    public boolean isActive(Long mailboxId) {
        String key = mailboxKey(mailboxId);
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return false;
        }

        boolean active = Boolean.parseBoolean(value);
        if (active) {
            log.info("Batch clustering ACTIVE for mailbox [{}] (key={})", mailboxId, key);
        }
        return active;
    }

    private String mailboxKey(Long mailboxId) {
        return keyPrefix + ":mailbox:" + mailboxId;
    }
}
