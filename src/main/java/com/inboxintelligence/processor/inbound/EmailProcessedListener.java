package com.inboxintelligence.processor.inbound;

import com.inboxintelligence.processor.domain.EmailProcessingService;
import com.inboxintelligence.processor.model.EmailProcessedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailProcessedListener {

    private final EmailProcessingService emailProcessingService;

    @RabbitListener(queues = "#{@emailQueue.name}")
    public void handleEmailProcessedEvent(EmailProcessedEvent event) {
        log.info("Received EmailProcessedEvent for emailContentId: {}", event.emailContentId());
        emailProcessingService.processEmail(event.emailContentId());
    }
}
