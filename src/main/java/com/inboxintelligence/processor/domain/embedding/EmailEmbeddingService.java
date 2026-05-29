package com.inboxintelligence.processor.domain.embedding;

import com.inboxintelligence.persistence.model.enums.ProcessedStatus;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.processor.config.ModelProviderProperties;
import com.inboxintelligence.processor.domain.model.factory.ModelProvider;
import com.inboxintelligence.processor.domain.model.factory.ModelProviderFactory;
import com.inboxintelligence.processor.outbound.EmailClusteringPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.inboxintelligence.persistence.model.enums.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailEmbeddingService {

    private final EmailContentService emailContentService;
    private final EmailEnrichmentService emailEnrichmentService;
    private final ModelProviderFactory modelProviderFactory;
    private final ModelProviderProperties modelProviderProperties;
    private final EmailClusteringPublisher emailClusteringPublisher;

    public void generateEmbedding(Long emailContentId) {

        log.debug("Starting embedding for emailContent id: {}", emailContentId);

        EmailContent emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        EmailEnrichment enrichment = emailEnrichmentService
                .findByEmailContentId(emailContentId)
                .orElseGet(EmailEnrichment::new);

        if (enrichment.getEmbedding() != null && enrichment.getEmbedding().length > 0) {
            log.warn("EmailContent [id={}] already has embedding (status={}) — skipping redelivery", emailContentId, emailContent.getProcessedStatus());
            updateStatus(emailContent, EMBEDDING_GENERATED);
            emailClusteringPublisher.publishClusteringEvent(emailContent);
            return;
        }
        invokeEmbeddingGeneration(emailContent, enrichment);
    }

    private void invokeEmbeddingGeneration(EmailContent emailContent, EmailEnrichment enrichment) {
        try {
            ModelProvider modelProvider = modelProviderFactory.getProvider();

            updateStatus(emailContent, EMBEDDING_STARTED);

            String normalizedContent = enrichment.getNormalizedContent();

            if (!StringUtils.hasText(normalizedContent)) {
                log.warn("No normalized content for emailContent [id={}], marking failed", emailContent.getId());
                updateStatus(emailContent, EMBEDDING_FAILED);
                return;
            }

            float[] embedding = modelProvider.generateEmbedding(normalizedContent);

            enrichment.setGmailMailboxId(emailContent.getGmailMailboxId());
            enrichment.setEmailContentId(emailContent.getId());
            enrichment.setEmbedding(embedding);

            emailEnrichmentService.save(enrichment);

            updateStatus(emailContent, EMBEDDING_GENERATED);
            log.info("Embedded [id={}, dims={}, model={}]", emailContent.getId(), embedding.length, modelProviderProperties.ollama().embedding().modelName());

            emailClusteringPublisher.publishClusteringEvent(emailContent);

        } catch (Exception e) {
            log.error("Failed to embed emailContent [id={}]: {}", emailContent.getId(), e.getMessage(), e);
            updateStatus(emailContent, EMBEDDING_FAILED);
            throw e;
        }
    }

    private void updateStatus(EmailContent emailContent, ProcessedStatus status) {
        emailContent.setProcessedStatus(status);
        emailContentService.save(emailContent);
    }
}
