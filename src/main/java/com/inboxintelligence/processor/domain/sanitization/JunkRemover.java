package com.inboxintelligence.processor.domain.sanitization;

import com.inboxintelligence.processor.config.SanitizationPatternProperties;
import com.inboxintelligence.processor.config.SanitizationStep;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Slf4j
@RequiredArgsConstructor
@SanitizationStep(order = 3, description = "Remove quoted replies, signatures, sign-offs, and disclaimers")
public class JunkRemover {

    private static final int MAX_SIGNATURE_LINES = 8;
    private static final int MIN_DISCLAIMER_KEYWORD_HITS = 2;
    private final SanitizationPatternProperties properties;
    private List<Pattern> quotedTextPatterns;
    private List<Pattern> signaturePatterns;

    @PostConstruct
    void init() {
        quotedTextPatterns = compilePatterns(properties.quotedTextPatterns(), "quoted-text");
        signaturePatterns = compilePatterns(properties.signaturePatterns(), "signature");
        log.info("Compiled {} quoted-text and {} signature patterns", quotedTextPatterns.size(), signaturePatterns.size());
    }

    private List<Pattern> compilePatterns(List<String> patterns, String category) {
        return patterns.stream().map(regex -> {
            try {
                return Pattern.compile(regex);
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("Invalid " + category + " regex pattern: " + regex, e);
            }
        }).toList();
    }

    public String process(String content) {

        String result = content;
        result = removeQuotedText(result);
        result = removeSignature(result);
        result = removeDisclaimers(result);
        return result;
    }


    private String removeQuotedText(String content) {

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


    private String removeSignature(String content) {

        String[] lines = content.split("\n");
        int searchFrom = Math.max(0, lines.length - MAX_SIGNATURE_LINES);

        for (int i = lines.length - 1; i >= searchFrom; i--) {
            if (matchesSignaturePattern(lines[i]) || matchesSignOffPhrase(lines[i])) {
                log.debug("Signature/sign-off detected at line {}", i);
                return String.join("\n", List.of(lines).subList(0, i));
            }
        }

        return content;
    }

    private boolean matchesSignaturePattern(String line) {
        for (Pattern pattern : signaturePatterns) {
            if (pattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSignOffPhrase(String line) {
        String lineLower = line.trim().toLowerCase();
        for (String phrase : properties.signOffPhrases()) {
            if (lineLower.startsWith(phrase)) {
                return true;
            }
        }
        return false;
    }

    private String removeDisclaimers(String content) {

        List<String> keywords = properties.disclaimerKeywords();
        List<String> paragraphs = new ArrayList<>(Arrays.asList(content.split("\n\\s*\n")));

        int removed = 0;
        while (!paragraphs.isEmpty()) {
            String lastParagraph = paragraphs.getLast().toLowerCase();
            long hits = keywords.stream().filter(lastParagraph::contains).count();

            if (hits >= MIN_DISCLAIMER_KEYWORD_HITS) {
                paragraphs.removeLast();
                removed++;
            } else {
                break;
            }
        }

        if (removed > 0) {
            log.debug("Removed {} disclaimer paragraph(s)", removed);
        }

        if (paragraphs.isEmpty()) {
            return "";
        }

        return String.join("\n\n", paragraphs);
    }
}
