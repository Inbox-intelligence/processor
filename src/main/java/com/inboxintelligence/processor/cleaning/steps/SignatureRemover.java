package com.inboxintelligence.processor.cleaning.steps;

import com.inboxintelligence.processor.cleaning.SanitizationStep;
import com.inboxintelligence.processor.config.CleaningPatternProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Pattern;

@SanitizationStep(order = 4, description = "Remove email signatures and sign-off phrases")
@Slf4j
public class SignatureRemover {

    private static final int MAX_SIGNATURE_LINES = 8;

    private final List<Pattern> signaturePatterns;
    private final List<String> signOffPhrases;

    public SignatureRemover(CleaningPatternProperties properties) {
        this.signaturePatterns = properties.signatures().stream()
                .map(Pattern::compile)
                .toList();
        this.signOffPhrases = properties.signOffPhrases().stream()
                .map(String::toLowerCase)
                .toList();
    }

    public String clean(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        String[] lines = content.split("\n");

        // Check for explicit signature patterns (e.g. "-- ", "Sent from my iPhone")
        for (Pattern pattern : signaturePatterns) {
            for (int i = lines.length - 1; i >= 0; i--) {
                if (pattern.matcher(lines[i]).find()) {
                    int linesBelow = lines.length - i;
                    if (linesBelow <= MAX_SIGNATURE_LINES) {
                        log.debug("Signature pattern matched at line {}", i);
                        return joinLines(lines, 0, i);
                    }
                }
            }
        }

        // Check for sign-off phrases near the end (e.g. "Best regards")
        for (int i = lines.length - 1; i >= Math.max(0, lines.length - MAX_SIGNATURE_LINES); i--) {
            String lineLower = lines[i].trim().toLowerCase();
            for (String phrase : signOffPhrases) {
                if (lineLower.startsWith(phrase)) {
                    log.debug("Sign-off phrase '{}' found at line {}", phrase, i);
                    return joinLines(lines, 0, i);
                }
            }
        }

        return content;
    }

    private String joinLines(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
