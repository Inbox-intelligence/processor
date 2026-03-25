package com.inboxintelligence.processor.cleaning.steps;

import com.inboxintelligence.processor.cleaning.SanitizationStep;
import com.inboxintelligence.processor.config.CleaningPatternProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@SanitizationStep(order = 5, description = "Remove legal disclaimers using keyword matching")
@Slf4j
public class DisclaimerRemover {

    private static final int MIN_KEYWORD_MATCHES = 2;

    private final List<String> disclaimerKeywords;

    public DisclaimerRemover(CleaningPatternProperties properties) {
        this.disclaimerKeywords = properties.disclaimerKeywords().stream()
                .map(String::toLowerCase)
                .toList();
    }

    public String clean(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        List<String> paragraphs = new ArrayList<>(Arrays.asList(content.split("\n\\s*\n")));

        // Scan from the end, remove trailing disclaimer paragraphs
        int removed = 0;
        while (!paragraphs.isEmpty()) {
            String lastParagraph = paragraphs.getLast().toLowerCase();
            long keywordHits = disclaimerKeywords.stream()
                    .filter(lastParagraph::contains)
                    .count();

            if (keywordHits >= MIN_KEYWORD_MATCHES) {
                paragraphs.removeLast();
                removed++;
            } else {
                break;
            }
        }

        if (removed > 0) {
            log.debug("Removed {} disclaimer paragraph(s)", removed);
        }

        // Don't strip everything — if nothing left, return original
        if (paragraphs.isEmpty()) {
            return content;
        }

        return String.join("\n\n", paragraphs);
    }
}
