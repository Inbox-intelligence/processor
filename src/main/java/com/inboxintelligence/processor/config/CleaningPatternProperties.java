package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "cleaning.patterns")
public record CleaningPatternProperties(
        List<String> quotedText,
        List<String> signatures,
        List<String> signOffPhrases,
        List<String> disclaimerKeywords
) {
}
