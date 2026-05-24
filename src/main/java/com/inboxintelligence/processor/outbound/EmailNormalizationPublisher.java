package com.inboxintelligence.processor.outbound;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.processor.config.EmailEventRabbitMQProperties;
import com.inboxintelligence.processor.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import static com.inboxintelligence.persistence.model.ProcessedStatus.PUBLISHED_FOR_NORMALIZATION;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNormalizationPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final EmailEventRabbitMQProperties properties;
    private final EmailContentService emailContentService;

    public void publishNormalizationEvent(EmailContent emailContent) {

        EmailEvent event = new EmailEvent(emailContent.getId());
        rabbitTemplate.convertAndSend(properties.exchange(), properties.normalizationRoutingKey(), event);
        emailContent.setProcessedStatus(PUBLISHED_FOR_NORMALIZATION);
        emailContentService.save(emailContent);
        log.debug("Published EmailNormalizationEvent for event: {}", event);
    }
}
