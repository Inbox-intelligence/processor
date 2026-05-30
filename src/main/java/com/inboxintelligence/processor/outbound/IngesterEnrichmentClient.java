package com.inboxintelligence.processor.outbound;

import com.inboxintelligence.persistence.model.enums.Category;
import com.inboxintelligence.persistence.model.enums.Importance;
import com.inboxintelligence.processor.config.IngesterProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

// Fire-and-forget. Failures here must not fail normalisation — the enrichment
// row is already persisted; the Gmail-side label flip is a downstream
// side-effect. Either field may be null to leave that dimension untouched.
@Slf4j
@Component
public class IngesterEnrichmentClient {

    private static final String PATH = "/gmail-api/messages/enrichment";

    private final RestClient restClient;
    private final String baseUrl;

    public IngesterEnrichmentClient(RestClient restClient, IngesterProperties properties) {
        this.restClient = restClient;
        this.baseUrl = trimTrailingSlash(properties.baseUrl());
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    public boolean applyEnrichment(String mailboxAddress,
                                   String messageId,
                                   Importance importance,
                                   Category category) {

        if (importance == null && category == null) {
            return false;
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Ingester base-url is not configured — skipping enrichment call [messageId={}, importance={}, category={}]",
                    messageId, importance, category);
            return false;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("messageId", messageId);
        if (importance != null) body.put("importance", importance.name());
        if (category != null) body.put("category", category.name());

        String uri = UriComponentsBuilder.fromUriString(baseUrl + PATH)
                .queryParam("mailboxAddress", mailboxAddress)
                .build()
                .toUriString();

        try {
            restClient.post()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Enrichment forwarded to ingester [mailbox={}, messageId={}, importance={}, category={}]",
                    mailboxAddress, messageId, importance, category);
            return true;
        } catch (Exception e) {
            log.warn("Failed to forward enrichment to ingester [mailbox={}, messageId={}, importance={}, category={}, error={}]",
                    mailboxAddress, messageId, importance, category, e.getMessage());
            return false;
        }
    }
}
