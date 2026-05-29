package com.inboxintelligence.processor.inbound;

import com.inboxintelligence.processor.domain.embedding.EmailEmbeddingService;
import com.inboxintelligence.processor.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailEmbeddingListener {

    private final EmailEmbeddingService emailEmbeddingService;

    @RabbitListener(queues = "#{@emailEmbeddingQueue.name}")
    public void handleEmailSanitizedEvent(EmailEvent event) {
        log.debug("Received EmailSanitizedEvent for emailContentId: {}", event.emailContentId());
        emailEmbeddingService.generateEmbedding(event.emailContentId());
    }
}
