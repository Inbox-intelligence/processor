package com.inboxintelligence.processor.domain.normalization;

import com.inboxintelligence.persistence.model.entity.EmailAttachment;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.model.enums.Category;
import com.inboxintelligence.persistence.model.enums.Importance;
import com.inboxintelligence.persistence.model.enums.ProcessedStatus;
import com.inboxintelligence.persistence.service.EmailAttachmentService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.persistence.service.GmailMailboxService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.model.NormalisedEmailResponse;
import com.inboxintelligence.processor.outbound.EmailEmbeddingPublisher;
import com.inboxintelligence.processor.outbound.IngesterEnrichmentClient;
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
    private final EmailStorageProviderFactory storageProviderFactory;
    private final NormalizationPromptHelper normalizationPromptHelper;
    private final EmailEmbeddingPublisher emailEmbeddingPublisher;
    private final IngesterEnrichmentClient ingesterEnrichmentClient;
    private final GmailMailboxService gmailMailboxService;

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

            NormalisedEmailResponse result = normalizationPromptHelper.normalise(
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
            emailEnrichmentService.save(enrichment);

            updateStatus(emailContent, NORMALIZATION_COMPLETED);
            log.info("Normalized [id={}, {} -> {} chars]", emailContent.getId(), sanitizedContent.length(), normalized.length());

            this.cleanup(emailContent);
            this.enrichEmail(emailContent, enrichment, result);
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

    private void cleanup(EmailContent emailContent) {
        try {
            EmailStorageProvider provider = storageProviderFactory.getProvider();

            String rawPath = emailContent.getRawContentPath();
            String sanitizedPath = emailContent.getSanitizedContentPath();

            provider.deleteContent(rawPath);
            provider.deleteContent(sanitizedPath);
            emailAttachmentService.deleteAllByEmailContentId(emailContent.getId(), provider);

            if (rawPath != null || sanitizedPath != null) {
                emailContent.setRawContentPath(null);
                emailContent.setSanitizedContentPath(null);
                emailContentService.save(emailContent);
                log.debug("Cleaned up raw/sanitized/attachment artifacts for emailContent [id={}]", emailContent.getId());
            }
        } catch (Exception e) {
            log.error("Cleanup of raw artifacts failed for emailContent [id={}] — leaving files in place: {}", emailContent.getId(), e.getMessage());
        }
    }

    public void enrichEmail(EmailContent emailContent, EmailEnrichment enrichment, NormalisedEmailResponse result) {

        String mailboxAddress = gmailMailboxService.findById(emailContent.getGmailMailboxId())
                .orElseThrow(() -> new IllegalStateException("Gmail mailbox not found for emailContent [id=" + emailContent.getId() + "]"))
                .getEmailAddress();

        ingesterEnrichmentClient.applyEnrichment(
                mailboxAddress,
                emailContent.getMessageId(),
                result.importance().name(),
                result.category().name());

        enrichment.setImportance(result.importance());
        enrichment.setImportanceReason(result.importanceReason());
        enrichment.setCategory(result.category());
        enrichment.setCategoryReason(result.categoryReason());
        emailEnrichmentService.save(enrichment);

        log.debug("Persisted enrichment importance/category for emailContent [id={}, importance={}, category={}]", emailContent.getId(), result.importance(), result.category());
    }
}
