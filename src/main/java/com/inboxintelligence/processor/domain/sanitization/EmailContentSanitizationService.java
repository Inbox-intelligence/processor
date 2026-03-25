package com.inboxintelligence.processor.domain.sanitization;

import com.inboxintelligence.processor.config.ContentSanitizationPipelineRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailContentSanitizationService {

    private final ContentSanitizationPipelineRegistry pipelineRegistry;

    /**
     * Cleans email content by running it through the sanitization pipeline.
     * Prefers HTML content over plain text (HTML has more structure to extract from).
     * Falls back to original if pipeline strips too aggressively.
     */
    public String clean(String htmlContent, String plainTextContent) {
        boolean isHtml = htmlContent != null && !htmlContent.isBlank();
        String raw = isHtml ? htmlContent : plainTextContent;

        if (raw == null || raw.isBlank()) {
            return "";
        }

        int originalLength = raw.length();
        String result = pipelineRegistry.execute(raw);

        if (result.length() < 20 && result.length() < originalLength * 0.1) {
            log.warn("Pipeline removed too much content ({} -> {} chars), falling back to original",
                    originalLength, result.length());
            return raw;
        }

        log.debug("Cleaned email content [source={}, {} -> {} chars]",
                isHtml ? "html" : "plaintext", originalLength, result.length());
        return result;
    }
}
