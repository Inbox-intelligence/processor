package com.inboxintelligence.processor.domain.normalization;

import com.inboxintelligence.persistence.model.ProcessedStatus;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.config.LlmProviderProperties;
import com.inboxintelligence.processor.domain.model.factory.ModelProvider;
import com.inboxintelligence.processor.domain.model.factory.ModelProviderFactory;
import com.inboxintelligence.processor.outbound.EmailEmbeddingPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNormalizationService {

    private static final int MAX_NORMALIZED_CHARS = 1000;
    private static final int MAX_PROMPT_INPUT_CHARS = 6000;

    private static final String EMAIL_PLACEHOLDER = "{{EMAIL}}";
    private static final String MAX_CHARS_PLACEHOLDER = "{{MAX_CHARS}}";

    private static final String PROMPT_TEMPLATE = """
            Rewrite the email below as a dense, factual summary optimized for semantic search.
            Rules:
            - Maximum {{MAX_CHARS}} characters.
            - Plain text only. No preamble, no markdown, no bullet points.
            - Preserve concrete entities (people, companies, products, dates, amounts, IDs).
            - Drop greetings, signatures, disclaimers, and pleasantries.
            - Output the summary ONLY - no additional text.

            EMAIL:
            {{EMAIL}}
            """;

    private final EmailContentService emailContentService;
    private final EmailEnrichmentService emailEnrichmentService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final ModelProviderFactory modelProviderFactory;
    private final LlmProviderProperties llmProviderProperties;
    private final EmailEmbeddingPublisher emailEmbeddingPublisher;

    public void normalizeEmail(Long emailContentId) {

        log.info("Starting normalization for emailContent id: {}", emailContentId);

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
            ModelProvider modelProvider = modelProviderFactory.getProvider();

            updateStatus(emailContent, NORMALIZATION_STARTED);

            String sanitizedContent = storageProvider.readContent(emailContent.getSanitizedContentPath());

            if (!StringUtils.hasText(sanitizedContent)) {
                log.warn("No sanitized content for emailContent [id={}], marking failed", emailContent.getId());
                updateStatus(emailContent, NORMALIZATION_FAILED);
                return;
            }

            String promptInput = sanitizedContent.length() > MAX_PROMPT_INPUT_CHARS
                    ? sanitizedContent.substring(0, MAX_PROMPT_INPUT_CHARS)
                    : sanitizedContent;

            String prompt = PROMPT_TEMPLATE
                    .replace(MAX_CHARS_PLACEHOLDER, String.valueOf(MAX_NORMALIZED_CHARS))
                    .replace(EMAIL_PLACEHOLDER, promptInput);

            String generated = modelProvider.generate(prompt).trim();
            String normalized = generated.length() > MAX_NORMALIZED_CHARS
                    ? generated.substring(0, MAX_NORMALIZED_CHARS)
                    : generated;

            log.info("Normalized emailContent [id={}, {} -> {} chars]", emailContent.getId(), sanitizedContent.length(), normalized.length());

            enrichment.setGmailMailboxId(emailContent.getGmailMailboxId());
            enrichment.setEmailContentId(emailContent.getId());
            enrichment.setNormalizedContent(normalized);

            emailEnrichmentService.save(enrichment);

            updateStatus(emailContent, NORMALIZATION_COMPLETED);
            log.info("EmailContent [id={}] normalization persisted [model={}]", emailContent.getId(), llmProviderProperties.model());

            emailEmbeddingPublisher.publishEmbeddingEvent(emailContent);
            log.info("EmailContent [id={}] queued for embedding", emailContent.getId());

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
}
