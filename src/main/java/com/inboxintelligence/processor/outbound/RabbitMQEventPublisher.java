package com.inboxintelligence.processor.outbound;

import com.inboxintelligence.processor.config.EmailEmbeddingQueueProperties;
import com.inboxintelligence.processor.model.EmailSanitizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitMQEventPublisher implements EventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final EmailEmbeddingQueueProperties embeddingProperties;

    @Override
    public void publishEmbeddingEvent(Long emailContentId) {

        rabbitTemplate.convertAndSend(
                embeddingProperties.exchange(),
                embeddingProperties.routingKey(),
                new EmailSanitizedEvent(emailContentId)
        );
        log.debug("Published EmailSanitizedEvent for emailContentId: {}", emailContentId);
    }
}
