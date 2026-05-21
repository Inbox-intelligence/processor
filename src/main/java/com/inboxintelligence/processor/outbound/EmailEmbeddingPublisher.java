package com.inboxintelligence.processor.outbound;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.processor.config.EmailEventRabbitMQProperties;
import com.inboxintelligence.processor.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import static com.inboxintelligence.persistence.model.ProcessedStatus.PUBLISHED_FOR_EMBEDDING;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailEmbeddingPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final EmailEventRabbitMQProperties properties;
    private final EmailContentService emailContentService;

    public void publishEmbeddingEvent(EmailContent emailContent) {

        EmailEvent event = new EmailEvent(emailContent.getId());
        rabbitTemplate.convertAndSend(properties.exchange(), properties.embeddingRoutingKey(), event);
        emailContentService.updateStatusAndNote(emailContent, PUBLISHED_FOR_EMBEDDING, null);
        log.debug("Published EmailEmbeddingEvent for event: {}", event);
    }
}
