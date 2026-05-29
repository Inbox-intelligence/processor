package com.inboxintelligence.processor.inbound;

import com.inboxintelligence.processor.domain.sanitization.EmailSanitizationService;
import com.inboxintelligence.processor.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailSanitizationListener {

    private final EmailSanitizationService emailSanitizationService;

    @RabbitListener(queues = "#{@emailSanitizationQueue.name}")
    public void handleEmailProcessedEvent(EmailEvent event) {
        log.debug("Received EmailEvent for emailContentId: {}", event.emailContentId());
        emailSanitizationService.sanitizeEmail(event.emailContentId());
    }
}
