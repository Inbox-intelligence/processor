package com.inboxintelligence.processor.domain;

import com.inboxintelligence.persistence.model.entity.EmailContent;
import com.inboxintelligence.persistence.service.EmailAttachmentService;
import com.inboxintelligence.persistence.service.EmailContentService;
import com.inboxintelligence.persistence.storage.EmailStorageProvider;
import com.inboxintelligence.persistence.storage.EmailStorageProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RawArtifactCleanupHelper {

    private final EmailContentService emailContentService;
    private final EmailAttachmentService emailAttachmentService;
    private final EmailStorageProviderFactory storageProviderFactory;

    public void cleanup(EmailContent emailContent) {
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
            log.warn("Cleanup of raw artifacts failed for emailContent [id={}] — leaving files in place: {}",
                    emailContent.getId(), e.getMessage());
        }
    }
}
