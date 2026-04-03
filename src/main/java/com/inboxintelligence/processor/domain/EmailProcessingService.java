package com.inboxintelligence.processor.domain;

import com.inboxintelligence.processor.domain.sanitization.ContentSanitizationPipelineRegistry;
import com.inboxintelligence.persistence.model.ProcessedStatus;
import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

import static com.inboxintelligence.persistence.model.ProcessedStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailProcessingService {

    private final EmailContentService emailContentService;
    private final EmailStorageProviderFactory storageProviderFactory;
    private final ContentSanitizationPipelineRegistry pipelineRegistry;

    public void processEmail(Long emailContentId) {

        var emailContent = emailContentService
                .findById(emailContentId)
                .orElseThrow(() -> new RuntimeException("EmailContent not found for id: " + emailContentId));

        try {
            emailContent.setProcessedStatus(PROCESSING_STARTED);
            emailContentService.save(emailContent);

            String cleanedText = sanitizeEmailContent(emailContent);

            String processedContentPath = storeProcessedContent(emailContent, cleanedText);
            emailContent.setProcessedContentPath(processedContentPath);

            emailContent.setProcessedStatus(PROCESSING_COMPLETED);
            emailContentService.save(emailContent);

            log.info("EmailContent [id={}] processed successfully", emailContentId);

        } catch (Exception e) {

            log.error("Failed to process emailContent [id={}]", emailContentId, e);

            emailContent.setProcessedStatus(PROCESSING_FAILED);
            emailContentService.save(emailContent);
        }
    }

    private String storeProcessedContent(EmailContent email, String cleanedText) {

        if (!StringUtils.hasText(cleanedText)) {
            return null;
        }

        String existingPath = StringUtils.hasText(email.getBodyHtmlContentPath())
                ? email.getBodyHtmlContentPath()
                : email.getBodyContentPath();

        String directoryPath = Path.of(existingPath).getParent().toString();
        var provider = storageProviderFactory.getProvider();

        return provider.writeContent(directoryPath, "processed_content.txt", cleanedText);
    }

    private String sanitizeEmailContent(EmailContent email) {

        var provider = storageProviderFactory.getProvider();

        String bodyContent = provider.readContent(email.getBodyContentPath());
        String htmlContent = provider.readContent(email.getBodyHtmlContentPath());
        log.debug("Content for email [id={}]: body={} chars, html={} chars", email.getId(), bodyContent.length(), htmlContent.length());

        String rawContent;

        if (StringUtils.hasText(htmlContent)) {
            rawContent = htmlContent;
        } else if (StringUtils.hasText(bodyContent)) {
            rawContent = bodyContent;
        } else {
            log.warn("No content found for email [id={}]", email.getId());
            return "";
        }

        int originalLength = rawContent.length();
        String cleanedText = pipelineRegistry.executeSanitizationPipeline(rawContent);

        if (cleanedText.length() < 20 && cleanedText.length() < originalLength * 0.1) {
            log.warn("Pipeline removed too much content ({} -> {} chars), falling back to original", originalLength, cleanedText.length());
            return rawContent;
        }

        log.info("Sanitized email [id={}, {} -> {} chars]", email.getId(), originalLength, cleanedText.length());
        return cleanedText;
    }
}
