package com.inboxintelligence.processor.domain;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.domain.sanitization.ContentSanitizationPipelineRegistry;
import com.inboxintelligence.processor.outbound.EventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailProcessingService {

    private final EventPublisher eventPublisher;
    private final EmailContentService emailContentService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final ContentSanitizationPipelineRegistry pipelineRegistry;

    public void processEmail(Long emailContentId) {

        var emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new RuntimeException("EmailContent not found for id: " + emailContentId));

        try {

            sanitize(emailContent);
            eventPublisher.publishEmbeddingEvent(emailContentId);
            log.info("EmailContent [id={}] sanitized and queued for embedding", emailContentId);

        } catch (Exception e) {
            log.error("Failed to process emailContent [id={}]", emailContentId, e);
            emailContent.setProcessedStatus(PROCESSING_FAILED);
            emailContentService.save(emailContent);
        }
    }

    public void sanitize(EmailContent emailContent) {

        log.info("Starting sanitization for email id: {}", emailContent.getId());
        var provider = storageProviderFactory.getProvider();

        emailContentService.updateProcessedStatus(emailContent, SANITIZATION_STARTED);

        String html = provider.readContent(emailContent.getBodyHtmlContentPath());
        String body = provider.readContent(emailContent.getBodyContentPath());


        String rawContent = StringUtils.hasText(html) ? html : StringUtils.hasText(body) ? body : "";

        if (StringUtils.hasText(rawContent)) {

            int originalLength = rawContent.length();
            String cleanedText = pipelineRegistry.executeSanitizationPipeline(rawContent);

            if (cleanedText.length() < 20 && cleanedText.length() < originalLength * 0.1) {
                log.warn("Pipeline removed too much content ({} -> {} chars), falling back to original", originalLength, cleanedText.length());
                cleanedText = rawContent;
            }

            log.info("Sanitized email [id={}, {} -> {} chars]", emailContent.getId(), originalLength, cleanedText.length());

            String processedContentPath = provider.writeContent(emailContent.getId(), emailContent.getMessageId(), "processed_content.txt", cleanedText);

            emailContent.setProcessedContentPath(processedContentPath);
            emailContent.setProcessedStatus(SANITIZATION_COMPLETED);
            emailContentService.save(emailContent);

            log.info("Sanitized content stored at: {} for email id: {}", processedContentPath, emailContent.getId());
        }

    }
}
