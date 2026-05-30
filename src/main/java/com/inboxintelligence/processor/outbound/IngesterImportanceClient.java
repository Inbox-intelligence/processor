package com.inboxintelligence.processor.outbound;

import com.inboxintelligence.persistence.model.enums.Importance;
import com.inboxintelligence.processor.config.IngesterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

// Fire-and-forget. Failures here must not fail normalisation — the enrichment
// is already persisted; the Gmail flag is a downstream side-effect.
@Slf4j
@Component
public class IngesterImportanceClient {

    private static final String PATH = "/gmail-api/messages/importance";

    private final RestClient restClient;
    private final String baseUrl;

    public IngesterImportanceClient(RestClient restClient, IngesterProperties properties) {
        this.restClient = restClient;
        this.baseUrl = trimTrailingSlash(properties.baseUrl());
    }

    public boolean applyImportance(String mailboxAddress, String messageId, Importance importance) {

        if (importance == null || importance == Importance.LOW) {
            return false;
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Ingester base-url is not configured — skipping importance call [messageId={}, importance={}]", messageId, importance);
            return false;
        }

        try {
            restClient.post()
                    .uri(baseUrl + PATH)
                    .header("Content-Type", "application/json")
                    .body(Map.of(
                            "mailboxAddress", mailboxAddress,
                            "messageId", messageId,
                            "importance", importance.name()))
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Importance forwarded to ingester [mailbox={}, messageId={}, importance={}]",
                    mailboxAddress, messageId, importance);
            return true;
        } catch (Exception e) {
            log.warn("Failed to forward importance to ingester [mailbox={}, messageId={}, importance={}, error={}]",
                    mailboxAddress, messageId, importance, e.getMessage());
            return false;
        }
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
