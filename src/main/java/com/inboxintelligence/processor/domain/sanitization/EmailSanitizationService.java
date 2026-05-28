package com.inboxintelligence.processor.domain.sanitization;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailAttachmentService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.domain.sanitization.pipeline.ContentSanitizationPipelineRegistry;
import com.inboxintelligence.processor.outbound.EmailNormalizationPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Set;

import static com.inboxintelligence.persistence.model.enums.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSanitizationService {

    private static final String HTML = "HTML";
    private static final String TEXT = "TEXT";
    private static final String JSON = "JSON";

    private final EmailNormalizationPublisher emailNormalizationPublisher;
    private final EmailContentService emailContentService;
    private final EmailAttachmentService emailAttachmentService;
    private final GmailMailboxService gmailMailboxService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final ContentSanitizationPipelineRegistry pipelineRegistry;

    public void sanitizeEmail(Long emailContentId) {

        log.info("Starting sanitization for email id: {}", emailContentId);

        EmailStorageProvider provider = storageProviderFactory.getProvider();

        EmailContent emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        String sanitizedContent = provider.readContent(emailContent.getSanitizedContentPath());

        if (StringUtils.hasText(sanitizedContent)) {
            log.warn("EmailContent [id={}] already past sanitization (status={}) — skipping redelivery", emailContentId, emailContent.getProcessedStatus());
            markStatusAndPublishForNormalization(provider, emailContent);
        } else {
            invokeSanitization(provider, emailContent);
        }
    }

    private void invokeSanitization(EmailStorageProvider provider, EmailContent emailContent) {

        try {
            updateStatus(emailContent, SANITIZATION_STARTED);

            String rawContent = provider.readContent(emailContent.getRawContentPath());

            if (!StringUtils.hasText(rawContent)) {
                log.warn("No raw content for email [id={}, path={}]", emailContent.getId(), emailContent.getRawContentPath());
                updateStatus(emailContent, SANITIZATION_FAILED);
                return;
            }

            String rawType = emailContent.getRawContentType();
            int originalLength = rawContent.length();


            if (!Set.of(HTML, TEXT).contains(rawType.toUpperCase(Locale.ROOT))) {
                log.warn("EmailContent [id={}] invalid rawContentType='{}'", emailContent.getId(), rawType);
                updateStatus(emailContent, SANITIZATION_FAILED);
                return;
            }

            String sanitizedContent = pipelineRegistry.executeSanitizationPipeline(rawContent);

            if (sanitizedContent.length() < 20 && sanitizedContent.length() < originalLength * 0.1) {
                log.warn("Pipeline removed too much content ({} -> {} chars), falling back to original", originalLength, sanitizedContent.length());
                sanitizedContent = rawContent;
            }

            log.info("Sanitized email [id={}, type={}, {} -> {} chars]", emailContent.getId(), rawType, originalLength, sanitizedContent.length());

            String email = gmailMailboxService.findById(emailContent.getGmailMailboxId())
                    .orElseThrow(() -> new IllegalStateException("Mailbox not found: " + emailContent.getGmailMailboxId()))
                    .getEmailAddress();


            String path = provider.writeContent(email, emailContent.getMessageId(), "processed_content.txt", sanitizedContent);

            emailContent.setSanitizedContentPath(path);
            log.info("Sanitized content stored at: {} for email id: {}", path, emailContent.getId());
            markStatusAndPublishForNormalization(provider, emailContent);
            log.info("EmailContent [id={}] sanitized and queued for normalization", emailContent.getId());

        } catch (Exception e) {
            log.error("Failed to process emailContent [id={}]: {}", emailContent.getId(), e.getMessage(), e);
            updateStatus(emailContent, SANITIZATION_FAILED);
            throw e;
        }
    }

    private void markStatusAndPublishForNormalization(EmailStorageProvider provider, EmailContent emailContent) {

        provider.deleteContent(emailContent.getRawContentPath());
        emailAttachmentService.deleteAllByEmailContentId(emailContent.getId(), provider);

        emailContent.setRawContentPath(null);
        updateStatus(emailContent, SANITIZATION_COMPLETED);

        emailNormalizationPublisher.publishNormalizationEvent(emailContent);
    }

    private void updateStatus(EmailContent emailContent, com.inboxintelligence.persistence.model.enums.ProcessedStatus status) {
        emailContent.setProcessedStatus(status);
        emailContentService.save(emailContent);
    }
}
