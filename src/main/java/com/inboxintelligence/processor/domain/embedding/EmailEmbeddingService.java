package com.inboxintelligence.processor.domain.embedding;

import com.inboxintelligence.persistence.model.entity.EmailEnrichment;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.service.EmailEnrichmentService;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.config.EmbeddingProviderProperties;
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
        var emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        try {

            var storageProvider = storageProviderFactory.getProvider();
            var embeddingProvider = embeddingProviderFactory.getProvider();

            emailContentService.updateStatusAndNote(emailContent, EMBEDDING_STARTED, null);

            String sanitizedContent = storageProvider.readContent(emailContent.getSanitizedContentPath());

            if (!StringUtils.hasText(sanitizedContent)) {
                log.warn("No sanitized content for emailContent [id={}], marking failed", emailContentId);
                emailContentService.updateStatusAndNote(emailContent, PROCESSING_FAILED, "No sanitized content found");
                return;
            }

            float[] embedding = embeddingProvider.generateEmbedding(sanitizedContent);
            log.info("EmailContent [id={}] embedding generated [dimensions={}]", emailContentId, embedding.length);

            EmailEnrichment enrichment = emailEnrichmentService.findByEmailContentId(emailContentId).orElseGet(EmailEnrichment::new);
            enrichment.setEmailContentId(emailContentId);
            enrichment.setEmbedding(embedding);
            enrichment.setEmbeddingModel(embeddingProviderProperties.model());
            emailEnrichmentService.save(enrichment);

            emailContentService.updateStatusAndNote(emailContent, EMBEDDING_GENERATED, null);
            log.info("EmailContent [id={}] embedding persisted [model={}]", emailContentId, embeddingProviderProperties.model());

            emailClusteringPublisher.publishClusteringEvent(emailContent);
            log.info("EmailContent [id={}] queued for cluster assignment", emailContentId);

        } catch (Exception e) {
            log.error("Failed to embed emailContent [id={}]", emailContentId, e);
            emailContentService.updateStatusAndNote(emailContent, PROCESSING_FAILED, e.getMessage());
            throw e;
        }
    }

}
