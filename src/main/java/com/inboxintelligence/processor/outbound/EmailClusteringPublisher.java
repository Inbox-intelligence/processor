package com.inboxintelligence.processor.outbound;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.processor.config.EmailEventRabbitMQProperties;
import com.inboxintelligence.processor.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailClusteringPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final EmailEventRabbitMQProperties properties;
    private final EmailContentService emailContentService;

    public void publishClusteringEvent(EmailContent emailContent) {
        var event = new EmailEvent(emailContent.getId());
        rabbitTemplate.convertAndSend(properties.exchange(), properties.clusteringRoutingKey(), event);
        emailContentService.updateStatusAndNote(emailContent, PUBLISHED_FOR_CLUSTER_ASSIGNMENT, null);
        log.debug("Published clustering event for emailContent [id={}]", emailContent.getId());
    }
}
