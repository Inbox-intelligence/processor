package com.inboxintelligence.processor.inbound;

import com.inboxintelligence.processor.domain.clustering.EmailClusteringService;
import com.inboxintelligence.processor.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailClusteringListener {

    private final EmailClusteringService emailClusteringService;

    @RabbitListener(queues = "#{@emailClusteringQueue.name}")
    public void handleEmbeddingGeneratedEvent(EmailEvent event) {
        log.info("Received clustering event for emailContentId: {}", event.emailContentId());
        emailClusteringService.assignCluster(event.emailContentId());
    }
}
