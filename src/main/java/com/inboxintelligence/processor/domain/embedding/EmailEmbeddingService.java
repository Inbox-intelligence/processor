package com.inboxintelligence.processor.domain.embedding;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import com.inboxintelligence.processor.config.EmbeddingVendorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailEmbeddingService {

    private final EmailContentService emailContentService;
    private final EmbeddingProviderFactory embeddingProviderFactory;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final EmbeddingVendorProperties embeddingVendorProperties;

    public void generateEmbedding(Long emailContentId) {

        EmailContent email = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new IllegalStateException("EmailContent not found: " + emailContentId));

        try {
            String cleanedText = readProcessedContent(email);

            if (!StringUtils.hasText(cleanedText)) {
                log.warn("No processed content for email [id={}], marking failed", emailContentId);
                emailContentService.updateProcessedStatus(email, PROCESSING_FAILED);
                return;
            }

            List<Double> vector = embeddingProviderFactory.getProvider().generateEmbedding(cleanedText);
            log.info("EmailContent [id={}] embedding generated [dimensions={}]", emailContentId, vector.size());

            emailContentService.updateProcessedStatus(email, EMBEDDING_GENERATED);

            email.setEmbedding(toFloatArray(vector));
            email.setEmbeddingModel(embeddingVendorProperties.model());
            email.setProcessedStatus(EMBEDDING_SAVED);
            emailContentService.save(email);

            log.info("EmailContent [id={}] embedding persisted [model={}]", emailContentId, embeddingVendorProperties.model());

        } catch (Exception e) {
            log.error("Failed to embed emailContent [id={}]", emailContentId, e);
            emailContentService.updateProcessedStatus(email, PROCESSING_FAILED);
            throw e;
        }
    }

    private float[] toFloatArray(List<Double> source) {
        float[] result = new float[source.size()];
        for (int i = 0; i < source.size(); i++) {
            result[i] = source.get(i).floatValue();
        }
        return result;
    }

    private String readProcessedContent(EmailContent emailContent) {

        String processedContentPath = emailContent.getProcessedContentPath();

        if (!StringUtils.hasText(processedContentPath)) {
            return "";
        }

        var provider = storageProviderFactory.getProvider();
        return Objects.requireNonNullElse(provider.readContent(processedContentPath), "");
    }
}
