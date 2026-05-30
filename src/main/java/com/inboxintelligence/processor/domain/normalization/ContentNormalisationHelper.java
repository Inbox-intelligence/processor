package com.inboxintelligence.processor.domain.normalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inboxintelligence.persistence.model.enums.Importance;
import com.inboxintelligence.processor.domain.model.factory.ModelProvider;
import com.inboxintelligence.processor.domain.model.factory.ModelProviderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Locale;

// Builds the prompts, calls the LLM, parses the JSON into NormalisedEmail.
// Bias lives in NormalisationBias so rules and recipient-context evolve apart.
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentNormalisationHelper {

    static final int MAX_NORMALIZED_CHARS = 5000;
    static final int MAX_PROMPT_INPUT_CHARS = 6000;
    static final int MAX_REASON_CHARS = 500;

    private static final String NO_CONTENT_MARKER = "NO_CONTENT";

    private static final String FROM_PLACEHOLDER = "{{FROM}}";
    private static final String SUBJECT_PLACEHOLDER = "{{SUBJECT}}";
    private static final String BODY_PLACEHOLDER = "{{BODY}}";
    private static final String MAX_CHARS_PLACEHOLDER = "{{MAX_CHARS}}";
    private static final String BIAS_PLACEHOLDER = "{{BIAS}}";

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            You triage emails. For each email, return a factual SUMMARY and an IMPORTANCE label.

            SUMMARY: up to {{MAX_CHARS}} chars, plain text, facts only.
            Summarise the email's CONTENT — do not describe what the email contains.
            Preserve codes, amounts, dates, and IDs verbatim.

            IMPORTANCE:
            - LOW: Default - marketing, newsletters, bank alerts, payments, order status, routine transactions, OTPs, generic notifications.
            - MEDIUM: Rare - real personal correspondence, statements, application updates, booking confirmations, .
            - HIGH: Very rare -the email body explicitly demands a time-bound action with a real consequence.

            REASON: one short sentence explaining on what basis IMPORTANCE value is assigned

            {{BIAS}}
            Output exactly one JSON object, nothing else:
            {"summary": string, "importance": "HIGH" | "MEDIUM" | "LOW", "reason": string}

            For empty or garbage content, respond with the bare text: {{NO_CONTENT}}
            """
            .replace("{{NO_CONTENT}}", NO_CONTENT_MARKER);

    private static final String USER_PROMPT_TEMPLATE = """
            From: {{FROM}}
            Subject: {{SUBJECT}}

            Body:
            {{BODY}}
            """;

    private final ModelProviderFactory modelProviderFactory;
    private final NormalisationBias normalisationBias;
    private final ObjectMapper objectMapper;

    // Returns null if the LLM returned no usable content or an unparseable response.
    public NormalisedEmail normalise(String fromAddress, String subject, String sanitizedBody) {

        String systemPrompt = buildSystemPrompt();
        String userPrompt = buildUserPrompt(fromAddress, subject, sanitizedBody);

        ModelProvider provider = modelProviderFactory.getLlmProvider();
        String raw = provider.invokeLlm(systemPrompt, userPrompt);
        String response = raw == null ? "" : raw.trim();

        if (response.isEmpty() || isNoContentResponse(response)) {
            log.warn("LLM produced no usable content [provider={}, inputChars={}, response=\"{}\"]",
                    provider.getClass().getSimpleName(),
                    sanitizedBody == null ? 0 : sanitizedBody.length(),
                    response.isEmpty() ? "<empty>" : preview(response));
            return null;
        }

        NormalisedEmail parsed = parse(response, provider);
        if (parsed == null) {
            return null;
        }

        log.debug("LLM normalised [provider={}, inputChars={}, summaryChars={}, importance={}]",
                provider.getClass().getSimpleName(),
                sanitizedBody == null ? 0 : sanitizedBody.length(),
                parsed.summary().length(),
                parsed.importance());
        return parsed;
    }

    public int maxNormalizedChars() {
        return MAX_NORMALIZED_CHARS;
    }

    private String buildSystemPrompt() {
        String biasBlock = normalisationBias.isActive()
                ? normalisationBias.text() + "\n"
                : "";
        return SYSTEM_PROMPT_TEMPLATE
                .replace(MAX_CHARS_PLACEHOLDER, String.valueOf(MAX_NORMALIZED_CHARS))
                .replace(BIAS_PLACEHOLDER, biasBlock);
    }

    private String buildUserPrompt(String fromAddress, String subject, String sanitizedBody) {
        String body = sanitizedBody == null ? "" : sanitizedBody;
        if (body.length() > MAX_PROMPT_INPUT_CHARS) {
            body = body.substring(0, MAX_PROMPT_INPUT_CHARS);
        }
        return USER_PROMPT_TEMPLATE
                .replace(FROM_PLACEHOLDER, fromAddress == null ? "" : fromAddress)
                .replace(SUBJECT_PLACEHOLDER, subject == null ? "" : subject)
                .replace(BODY_PLACEHOLDER, body);
    }

    private NormalisedEmail parse(String response, ModelProvider provider) {

        String json = stripCodeFences(response);
        int firstBrace = json.indexOf('{');
        int lastBrace = json.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            log.warn("LLM response is not JSON [provider={}, response=\"{}\"]",
                    provider.getClass().getSimpleName(), preview(response));
            return null;
        }
        json = json.substring(firstBrace, lastBrace + 1);

        try {
            JsonNode node = objectMapper.readTree(json);
            String summary = textOrEmpty(node.get("summary")).trim();
            Importance importance = parseImportance(textOrEmpty(node.get("importance")));
            String reason = textOrEmpty(node.get("reason")).trim();

            if (summary.isEmpty()) {
                log.warn("LLM JSON missing 'summary' [provider={}, response=\"{}\"]",
                        provider.getClass().getSimpleName(), preview(response));
                return null;
            }

            if (summary.length() > MAX_NORMALIZED_CHARS) {
                summary = summary.substring(0, MAX_NORMALIZED_CHARS);
            }
            if (reason.length() > MAX_REASON_CHARS) {
                reason = reason.substring(0, MAX_REASON_CHARS);
            }

            return new NormalisedEmail(summary, importance, reason);

        } catch (Exception e) {
            log.warn("Failed to parse LLM JSON [provider={}, error={}, response=\"{}\"]",
                    provider.getClass().getSimpleName(), e.getMessage(), preview(response));
            return null;
        }
    }

    private static String stripCodeFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String textOrEmpty(JsonNode node) {
        return (node == null || node.isNull()) ? "" : node.asText("");
    }

    private static Importance parseImportance(String value) {
        if (value == null) return Importance.LOW;
        try {
            return Importance.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown importance value '{}' — defaulting to LOW", value);
            return Importance.LOW;
        }
    }

    private static boolean isNoContentResponse(String response) {
        if (response == null) return true;
        String marker = response.toUpperCase().replaceAll("[^A-Z]", "");
        return "NOCONTENT".equals(marker);
    }

    private static String preview(String s) {
        if (s == null) return "<null>";
        String oneLine = s.replace("\n", "\\n").replace("\r", "\\r");
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "…" : oneLine;
    }
}
