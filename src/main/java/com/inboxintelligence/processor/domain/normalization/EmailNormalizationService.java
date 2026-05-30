package com.inboxintelligence.processor.domain.normalization;

import com.inboxintelligence.persistence.model.enums.ProcessedStatus;
import com.inboxintelligence.persistence.model.entity.EmailAttachment;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.EmailAttachmentService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.outbound.EmailEmbeddingPublisher;
import com.inboxintelligence.processor.outbound.IngesterImportanceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

import static com.inboxintelligence.persistence.model.enums.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNormalizationService {

    private final EmailContentService emailContentService;
    private final EmailEnrichmentService emailEnrichmentService;
    private final EmailAttachmentService emailAttachmentService;
    private final GmailMailboxService gmailMailboxService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final ContentNormalisationHelper contentNormalisationHelper;
    private final IngesterImportanceClient ingesterImportanceClient;
    private final EmailEmbeddingPublisher emailEmbeddingPublisher;

    public void normalizeEmail(Long emailContentId) {

        log.debug("Starting normalization for emailContent id: {}", emailContentId);

        EmailContent emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        EmailEnrichment enrichment = emailEnrichmentService
                .findByEmailContentId(emailContentId)
                .orElseGet(EmailEnrichment::new);

        if (StringUtils.hasText(enrichment.getNormalizedContent())) {
            log.warn("EmailContent [id={}] already has normalized content (status={}) — skipping redelivery", emailContentId, emailContent.getProcessedStatus());
            updateStatus(emailContent, NORMALIZATION_COMPLETED);
            emailEmbeddingPublisher.publishEmbeddingEvent(emailContent);
            return;
        }

        invokeNormalization(emailContent, enrichment);
    }

    private void invokeNormalization(EmailContent emailContent, EmailEnrichment enrichment) {

        try {
            EmailStorageProvider storageProvider = storageProviderFactory.getProvider();

            updateStatus(emailContent, NORMALIZATION_STARTED);

            String sanitizedContent = storageProvider.readContent(emailContent.getSanitizedContentPath());

            if (!StringUtils.hasText(sanitizedContent)) {
                updateStatus(emailContent, NORMALIZATION_FAILED);
                log.debug("EmailContent [id={}] failure persisted (status=NORMALIZATION_FAILED)", emailContent.getId());
                return;
            }

            NormalisedEmail result = contentNormalisationHelper.normalise(
                    emailContent.getFromAddress(),
                    emailContent.getSubject(),
                    sanitizedContent);

            if (result == null) {
                log.warn("Normalization rejected for emailContent [id={}, sanitizedChars={}] — marking failed", emailContent.getId(), sanitizedContent.length());
                updateStatus(emailContent, NORMALIZATION_FAILED);
                return;
            }

            String normalized = prependHeaders(emailContent, result.summary());

            enrichment.setGmailMailboxId(emailContent.getGmailMailboxId());
            enrichment.setEmailContentId(emailContent.getId());
            enrichment.setNormalizedContent(normalized);
            enrichment.setImportance(result.importance());
            enrichment.setImportanceReason(result.reason());

            emailEnrichmentService.save(enrichment);

            updateStatus(emailContent, NORMALIZATION_COMPLETED);
            log.info("Normalized [id={}, {} -> {} chars, importance={}]",
                    emailContent.getId(), sanitizedContent.length(), normalized.length(), result.importance());

            forwardImportanceToIngester(emailContent, result);

            emailEmbeddingPublisher.publishEmbeddingEvent(emailContent);

        } catch (Exception e) {
            log.error("Failed to normalize emailContent [id={}]: {}", emailContent.getId(), e.getMessage(), e);
            updateStatus(emailContent, NORMALIZATION_FAILED);
            throw e;
        }
    }

    private void updateStatus(EmailContent emailContent, ProcessedStatus status) {
        emailContent.setProcessedStatus(status);
        emailContentService.save(emailContent);
    }

    private String prependHeaders(EmailContent emailContent, String body) {

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
                .toList();

        if (!attachmentNames.isEmpty())
            sb.append("Attachments: ").append(String.join(", ", attachmentNames)).append("\n");

        sb.append("Summary: ").append(body);
        return sb.toString();
    }

    // Fire-and-forget; enrichment is already saved.
    private void forwardImportanceToIngester(EmailContent emailContent, NormalisedEmail result) {

        if (result.importance() == null || result.importance() == com.inboxintelligence.persistence.model.enums.Importance.LOW) {
            return;
        }

        gmailMailboxService.findById(emailContent.getGmailMailboxId())
                .ifPresentOrElse(
                        mailbox -> ingesterImportanceClient.applyImportance(
                                mailbox.getEmailAddress(),
                                emailContent.getMessageId(),
                                result.importance()),
                        () -> log.warn("Mailbox {} not found — cannot forward importance for emailContent [id={}]",
                                emailContent.getGmailMailboxId(), emailContent.getId()));
    }
}
