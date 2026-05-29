package com.inboxintelligence.processor.inbound;

import com.inboxintelligence.processor.domain.normalization.EmailNormalizationService;
import com.inboxintelligence.processor.model.EmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailNormalizationListener {

    private final EmailNormalizationService emailNormalizationService;

    @RabbitListener(queues = "#{@emailNormalizationQueue.name}")
    public void handleEmailNormalizationEvent(EmailEvent event) {
        log.debug("Received EmailNormalizationEvent for emailContentId: {}", event.emailContentId());
        emailNormalizationService.normalizeEmail(event.emailContentId());
    }
}
