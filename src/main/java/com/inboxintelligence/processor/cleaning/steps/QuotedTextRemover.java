package com.inboxintelligence.processor.cleaning.steps;

import com.inboxintelligence.processor.cleaning.SanitizationStep;
import com.inboxintelligence.processor.config.CleaningPatternProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SanitizationStep(order = 3, description = "Remove quoted reply text using configurable patterns")
@Slf4j
public class QuotedTextRemover {

    private final List<Pattern> quotedTextPatterns;

    public QuotedTextRemover(CleaningPatternProperties properties) {
        this.quotedTextPatterns = properties.quotedText().stream()
                .map(Pattern::compile)
                .toList();
    }

    public String clean(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        int earliestMatch = content.length();

        for (Pattern pattern : quotedTextPatterns) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find() && matcher.start() < earliestMatch) {
                earliestMatch = matcher.start();
            }
        }

        if (earliestMatch < content.length()) {
            log.debug("Quoted text detected at position {}", earliestMatch);
            return content.substring(0, earliestMatch);
        }

        return content;
    }
}
