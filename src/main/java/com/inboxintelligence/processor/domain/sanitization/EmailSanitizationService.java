package com.inboxintelligence.processor.domain.sanitization;

import com.inboxintelligence.persistence.model.entity.EmailContent;
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

    // The bigger the raw input, the more reduction we tolerate — large HTML
    // emails are mostly CSS / layout chrome that legitimately strips away.
    private static final int MIN_USEFUL_CHARS = 50;
    private static final int SMALL_RAW_THRESHOLD = 2_000;
    private static final int LARGE_RAW_THRESHOLD = 10_000;
    private static final double SMALL_RAW_MAX_REDUCTION = 0.80;
    private static final double MEDIUM_RAW_MAX_REDUCTION = 0.95;
    private static final double LARGE_RAW_MAX_REDUCTION = 0.98;

    private final EmailNormalizationPublisher emailNormalizationPublisher;
    private final EmailContentService emailContentService;
    private final GmailMailboxService gmailMailboxService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final ContentSanitizationPipelineRegistry pipelineRegistry;

    private static boolean isOverStripped(Long emailContentId, String sanitized, int originalLength) {

        int sanitizedLength = sanitized.length();
        double reductionRatio = 1.0 - (double) sanitizedLength / Math.max(1, originalLength);
        long pctRemoved = Math.round(100.0 * reductionRatio);

        if (sanitizedLength < MIN_USEFUL_CHARS) {
            log.warn("Sanitization over-stripped [id={}, {} -> {} chars, {}% removed, reason=below-floor] — falling back to raw content for LLM", emailContentId, originalLength, sanitizedLength, pctRemoved);
            return true;
        }

        if (originalLength < SMALL_RAW_THRESHOLD && reductionRatio > SMALL_RAW_MAX_REDUCTION) {
            log.warn("Sanitization over-stripped [id={}, {} -> {} chars, {}% removed, reason=small-raw-high-reduction] — falling back to raw content for LLM", emailContentId, originalLength, sanitizedLength, pctRemoved);
            return true;
        }

        if (originalLength >= SMALL_RAW_THRESHOLD && originalLength < LARGE_RAW_THRESHOLD && reductionRatio > MEDIUM_RAW_MAX_REDUCTION) {
            log.warn("Sanitization over-stripped [id={}, {} -> {} chars, {}% removed, reason=medium-raw-severe-reduction] — falling back to raw content for LLM", emailContentId, originalLength, sanitizedLength, pctRemoved);
            return true;
        }

        if (originalLength >= LARGE_RAW_THRESHOLD && reductionRatio > LARGE_RAW_MAX_REDUCTION) {
            log.warn("Sanitization over-stripped [id={}, {} -> {} chars, {}% removed, reason=large-raw-extreme-reduction] — falling back to raw content for LLM", emailContentId, originalLength, sanitizedLength, pctRemoved);
            return true;
        }

        return false;
    }

    public void sanitizeEmail(Long emailContentId) {

        log.debug("Starting sanitization for email id: {}", emailContentId);

        EmailStorageProvider provider = storageProviderFactory.getProvider();

        EmailContent emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        String sanitizedContent = provider.readContent(emailContent.getSanitizedContentPath());

        if (StringUtils.hasText(sanitizedContent)) {
            log.warn("EmailContent [id={}] already past sanitization (status={}) — skipping redelivery", emailContentId, emailContent.getProcessedStatus());
            updateStatus(emailContent, SANITIZATION_COMPLETED);
            emailNormalizationPublisher.publishNormalizationEvent(emailContent);
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

            if (isOverStripped(emailContent.getId(), sanitizedContent, originalLength)) {
                sanitizedContent = rawContent;
            }

            log.info("Sanitized [id={}, {}, {} -> {} chars]", emailContent.getId(), rawType, originalLength, sanitizedContent.length());

            String email = gmailMailboxService.findById(emailContent.getGmailMailboxId())
                    .orElseThrow(() -> new IllegalStateException("Mailbox not found: " + emailContent.getGmailMailboxId()))
                    .getEmailAddress();


            String path = provider.writeContent(email, emailContent.getMessageId(), "processed_content.txt", sanitizedContent);

            emailContent.setSanitizedContentPath(path);
            log.debug("Sanitized content stored at {} [id={}]", path, emailContent.getId());

            updateStatus(emailContent, SANITIZATION_COMPLETED);
            emailNormalizationPublisher.publishNormalizationEvent(emailContent);


        } catch (Exception e) {
            log.error("Failed to process emailContent [id={}]: {}", emailContent.getId(), e.getMessage(), e);
            updateStatus(emailContent, SANITIZATION_FAILED);
            throw e;
        }
    }

    private void updateStatus(EmailContent emailContent, com.inboxintelligence.persistence.model.enums.ProcessedStatus status) {
        emailContent.setProcessedStatus(status);
        emailContentService.save(emailContent);
    }
}
