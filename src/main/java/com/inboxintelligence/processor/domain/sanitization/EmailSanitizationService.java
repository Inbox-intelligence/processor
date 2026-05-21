package com.inboxintelligence.processor.domain.sanitization;

import com.inboxintelligence.persistence.model.entity.EmailAttachment;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailAttachmentService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.domain.sanitization.pipeline.ContentSanitizationPipelineRegistry;
import com.inboxintelligence.processor.outbound.EmailEmbeddingPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailSanitizationService {


    private final EmailEmbeddingPublisher emailEmbeddingPublisher;
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
            markStatusAndPublishForEmbedding(provider, emailContent);
        } else {
            invokeSanitization(provider, emailContent);
        }

    }

    private void invokeSanitization(EmailStorageProvider provider, EmailContent emailContent) {

        try {

            emailContentService.updateStatusAndNote(emailContent, SANITIZATION_STARTED, null);

            String html = provider.readContent(emailContent.getBodyHtmlContentPath());
            String body = provider.readContent(emailContent.getBodyContentPath());

            String rawContent = StringUtils.hasText(html) ? html : StringUtils.hasText(body) ? body : "";

            if (!StringUtils.hasText(rawContent)) {
                log.warn("No raw content for email: {}", emailContent.getId());
                emailContentService.updateStatusAndNote(emailContent, SANITIZATION_FAILED, "No raw content");
                return;
            }

            int originalLength = rawContent.length();
            String cleanedText = pipelineRegistry.executeSanitizationPipeline(rawContent);

            if (cleanedText.length() < 20 && cleanedText.length() < originalLength * 0.1) {
                log.warn("Pipeline removed too much content ({} -> {} chars), falling back to original", originalLength, cleanedText.length());
                cleanedText = rawContent;
            }

            log.info("Sanitized email [id={}, {} -> {} chars]", emailContent.getId(), originalLength, cleanedText.length());

            String email = gmailMailboxService.findById(emailContent.getGmailMailboxId())
                    .orElseThrow(() -> new IllegalStateException("Mailbox not found: " + emailContent.getGmailMailboxId()))
                    .getEmailAddress();

            String enrichedContent = enrichSanitizedContent(emailContent, cleanedText);
            String path = provider.writeContent(email, emailContent.getMessageId(), "processed_content.txt", enrichedContent);

            emailContent.setSanitizedContentPath(path);
            log.info("Sanitized content stored at: {} for email id: {}", path, emailContent.getId());
            this.markStatusAndPublishForEmbedding(provider, emailContent);
            log.info("EmailContent [id={}] sanitized and queued for embedding", emailContent.getId());

        } catch (Exception e) {
            log.error("Failed to process emailContent [id={}]", emailContent.getId(), e);
            emailContentService.updateStatusAndNote(emailContent, SANITIZATION_FAILED, e.getMessage());
            throw e;
        }
    }

    private void markStatusAndPublishForEmbedding(EmailStorageProvider provider, EmailContent emailContent) {

        //Sanitized Content is always persisted rest is deleted
        provider.deleteContent(emailContent.getRawMessagePath());
        provider.deleteContent(emailContent.getBodyHtmlContentPath());
        provider.deleteContent(emailContent.getBodyContentPath());

        emailAttachmentService.deleteAllByEmailContentId(emailContent.getId(), provider);

        emailContent.setRawMessagePath(null);
        emailContent.setBodyHtmlContentPath(null);
        emailContent.setBodyContentPath(null);
        emailContentService.updateStatusAndNote(emailContent, SANITIZATION_COMPLETED, null);

        emailEmbeddingPublisher.publishEmbeddingEvent(emailContent);
    }

    private String enrichSanitizedContent(EmailContent emailContent, String sanitizedBody) {

        StringBuilder sb = new StringBuilder();

        if (StringUtils.hasText(emailContent.getFromAddress()))
            sb.append("From: ").append(emailContent.getFromAddress()).append("\n");
        if (StringUtils.hasText(emailContent.getToAddress()))
            sb.append("To: ").append(emailContent.getToAddress()).append("\n");
        if (StringUtils.hasText(emailContent.getSubject()))
            sb.append("Subject: ").append(emailContent.getSubject()).append("\n");

        List<String> attachmentNames = emailAttachmentService.findByEmailContentId(emailContent.getId())
                .stream()
                .filter(a -> !Boolean.TRUE.equals(a.getIsInline()))
                .map(EmailAttachment::getFileName)
                .collect(Collectors.toList());

        if (!attachmentNames.isEmpty())
            sb.append("Attachments: ").append(String.join(", ", attachmentNames)).append("\n");

        sb.append("Content: ").append(sanitizedBody);

        return sb.toString();
    }

}
