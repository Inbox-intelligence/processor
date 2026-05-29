package com.inboxintelligence.processor.domain.normalization;

import com.inboxintelligence.persistence.model.enums.ProcessedStatus;
import com.inboxintelligence.persistence.model.entity.EmailAttachment;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.EmailAttachmentService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.config.ModelProviderProperties;
import com.inboxintelligence.processor.domain.model.factory.ModelProvider;
import com.inboxintelligence.processor.domain.model.factory.ModelProviderFactory;
import com.inboxintelligence.processor.outbound.EmailEmbeddingPublisher;
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

    private static final int MAX_NORMALIZED_CHARS = 5000;
    private static final int MAX_PROMPT_INPUT_CHARS = 6000;

    private static final String EMAIL_PLACEHOLDER = "{{EMAIL}}";
    private static final String MAX_CHARS_PLACEHOLDER = "{{MAX_CHARS}}";

    private static final String PROMPT_TEMPLATE = """
            Rewrite the email below as a dense, factual summary optimized for semantic search.
            Rules:
            - Maximum {{MAX_CHARS}} characters.
            - Plain text only. No preamble, no markdown, no bullet points, no new-lines.
            - Use ONLY information actually present in the email. Do NOT invent, infer, or speculate.
            - Do NOT describe the email itself or how it was generated. Summarise its CONTENT.
            - Preserve concrete entities (people, companies, products, dates, amounts, IDs).
            - Drop greetings, signatures, disclaimers, pleasantries, and tracking/unsubscribe links.
            - If the email body is empty, gibberish, or has no meaningful content, reply with exactly: NO_CONTENT
            - Output the summary ONLY - no additional text.
            EMAIL:
            {{EMAIL}}
            """;

    private final EmailContentService emailContentService;
    private final EmailEnrichmentService emailEnrichmentService;
    private final EmailAttachmentService emailAttachmentService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final ModelProviderFactory modelProviderFactory;
    private final ModelProviderProperties modelProviderProperties;
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
            ModelProvider modelProvider = modelProviderFactory.getProvider();

            updateStatus(emailContent, NORMALIZATION_STARTED);

            String sanitizedContent = storageProvider.readContent(emailContent.getSanitizedContentPath());

            if (!StringUtils.hasText(sanitizedContent)) {
                updateStatus(emailContent, NORMALIZATION_FAILED);
                log.debug("EmailContent [id={}] failure persisted (status=NORMALIZATION_FAILED)", emailContent.getId());
                return;
            }

            String promptInput = sanitizedContent.length() > MAX_PROMPT_INPUT_CHARS
                    ? sanitizedContent.substring(0, MAX_PROMPT_INPUT_CHARS)
                    : sanitizedContent;

            String prompt = PROMPT_TEMPLATE
                    .replace(MAX_CHARS_PLACEHOLDER, String.valueOf(MAX_NORMALIZED_CHARS))
                    .replace(EMAIL_PLACEHOLDER, promptInput);

            String generated = modelProvider.generate(prompt).trim().replace("\n", " ").replace("\r", " ");

            String noContentMarker = generated.toUpperCase().replaceAll("[^A-Z]", "");
            if (generated.isEmpty() || "NOCONTENT".equals(noContentMarker)) {
                log.warn("Normalization rejected by LLM [id={}, sanitizedChars={}, llmResponse=\"{}\", inputPreview=\"{}\"] — marking failed",
                        emailContent.getId(),
                        sanitizedContent.length(),
                        generated.isEmpty() ? "<empty>" : generated,
                        preview(promptInput, 120));
                updateStatus(emailContent, NORMALIZATION_FAILED);
                log.debug("EmailContent [id={}] failure persisted (status=NORMALIZATION_FAILED)", emailContent.getId());
                return;
            }

            String prepended = prependHeaders(emailContent, generated);
            String normalized = prepended.length() > MAX_NORMALIZED_CHARS
                    ? prepended.substring(0, MAX_NORMALIZED_CHARS)
                    : prepended;

            enrichment.setGmailMailboxId(emailContent.getGmailMailboxId());
            enrichment.setEmailContentId(emailContent.getId());
            enrichment.setNormalizedContent(normalized);

            emailEnrichmentService.save(enrichment);

            updateStatus(emailContent, NORMALIZATION_COMPLETED);
            log.info("Normalized [id={}, {} -> {} chars, model={}]", emailContent.getId(), sanitizedContent.length(), normalized.length(), modelProviderProperties.ollama().llm().modelName());

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

    private static String preview(String s, int max) {
        if (s == null) return "<null>";
        String oneLine = s.replace("\n", "\\n").replace("\r", "\\r");
        return oneLine.length() > max ? oneLine.substring(0, max) + "…" : oneLine;
    }
}
