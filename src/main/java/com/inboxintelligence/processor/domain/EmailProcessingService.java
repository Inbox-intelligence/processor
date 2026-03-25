package com.inboxintelligence.processor.domain;

import com.inboxintelligence.processor.domain.sanitization.EmailContentSanitizationService;
import com.inboxintelligence.processor.model.ProcessedStatus;
import com.inboxintelligence.processor.persistence.service.EmailContentService;
import com.inboxintelligence.processor.persistence.storage.EmailStorageReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailProcessingService {

    private final EmailContentService emailContentService;
    private final EmailStorageReader emailStorageReader;
    private final EmailContentSanitizationService emailContentSanitizationService;

    @Transactional
    public void processEmail(Long emailContentId) {

        var emailContentOptional = emailContentService.findById(emailContentId);

        if (emailContentOptional.isEmpty()) {
            log.warn("EmailContent not found for id: {}", emailContentId);
            return;
        }

        var email = emailContentOptional.get();
        log.info("Processing email [id={}, subject='{}']", email.getId(), email.getSubject());

        email.setProcessedStatus(ProcessedStatus.PROCESSING_STARTED);
        emailContentService.save(email);

        String bodyContent = emailStorageReader.readContent(email.getBodyContentPath()).orElse("");
        String htmlContent = emailStorageReader.readContent(email.getBodyHtmlContentPath()).orElse("");
        log.debug("Loaded storage content for email [id={}]: body={} chars, html={} chars",
                email.getId(), bodyContent.length(), htmlContent.length());

        String cleanedText = emailContentSanitizationService.clean(htmlContent, bodyContent);
        log.info("Cleaned email [id={}, {} -> {} chars]", email.getId(), htmlContent.length() + bodyContent.length(), cleanedText.length());

        // TODO: Pass cleanedText to Intelligence Layer (LLM)
    }
}
