package com.inboxintelligence.processor.domain.embedding;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.config.EmbeddingProviderProperties;
import com.inboxintelligence.processor.domain.embedding.factory.EmbeddingProvider;
import com.inboxintelligence.processor.domain.embedding.factory.EmbeddingProviderFactory;
import com.inboxintelligence.processor.outbound.EmailClusteringPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailEmbeddingService {

    private final EmailContentService emailContentService;
    private final EmailEnrichmentService emailEnrichmentService;
    private final EmbeddingProviderFactory embeddingProviderFactory;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final EmbeddingProviderProperties embeddingProviderProperties;
    private final EmailClusteringPublisher emailClusteringPublisher;

    public void generateEmbedding(Long emailContentId) {

        log.info("Starting embedding for emailContent id: {}", emailContentId);

        EmailContent emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        EmailEnrichment enrichment = emailEnrichmentService
                .findByEmailContentId(emailContentId)
                .orElseGet(EmailEnrichment::new);

        if (enrichment.getEmbedding() != null && enrichment.getEmbedding().length > 0) {
            log.warn("EmailContent [id={}] already has embedding (status={}) — skipping redelivery", emailContentId, emailContent.getProcessedStatus());
            emailContentService.updateStatusAndNote(emailContent, EMBEDDING_GENERATED, null);
            emailClusteringPublisher.publishClusteringEvent(emailContent);
            return;
        }
        invokeEmbeddingGeneration(emailContent, enrichment);
    }

    private void invokeEmbeddingGeneration(EmailContent emailContent, EmailEnrichment enrichment) {
        try {

            EmailStorageProvider storageProvider = storageProviderFactory.getProvider();
            EmbeddingProvider embeddingProvider = embeddingProviderFactory.getProvider();

            emailContentService.updateStatusAndNote(emailContent, EMBEDDING_STARTED, null);

            String sanitizedContent = storageProvider.readContent(emailContent.getSanitizedContentPath());

            if (!StringUtils.hasText(sanitizedContent)) {
                log.warn("No sanitized content for emailContent [id={}], marking failed", emailContent.getId());
                emailContentService.updateStatusAndNote(emailContent, EMBEDDING_FAILED, "No sanitized content found");
                return;
            }

            float[] embedding = embeddingProvider.generateEmbedding(sanitizedContent);
            log.info("EmailContent [id={}] embedding generated [dimensions={}]", emailContent.getId(), embedding.length);

            enrichment.setEmailContentId(emailContent.getId());
            enrichment.setEmbedding(embedding);
            enrichment.setEmbeddingModel(embeddingProviderProperties.model());

            emailEnrichmentService.save(enrichment);

            emailContentService.updateStatusAndNote(emailContent, EMBEDDING_GENERATED, null);
            log.info("EmailContent [id={}] embedding persisted [model={}]", emailContent.getId(), embeddingProviderProperties.model());

            emailClusteringPublisher.publishClusteringEvent(emailContent);
            log.info("EmailContent [id={}] queued for cluster assignment", emailContent.getId());

        } catch (Exception e) {
            log.error("Failed to embed emailContent [id={}]", emailContent.getId(), e);
            emailContentService.updateStatusAndNote(emailContent, EMBEDDING_FAILED, e.getMessage());
            throw e;
        }
    }

}
