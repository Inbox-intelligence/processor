package com.inboxintelligence.processor.outbound;

public interface EventPublisher {

    void publishEmbeddingEvent(Long emailContentId);
}
