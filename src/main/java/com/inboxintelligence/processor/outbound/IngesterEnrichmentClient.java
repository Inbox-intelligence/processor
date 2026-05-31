package com.inboxintelligence.processor.outbound;

import com.inboxintelligence.persistence.model.enums.Category;
import com.inboxintelligence.persistence.model.enums.Importance;
import com.inboxintelligence.processor.config.IngesterProperties;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class IngesterEnrichmentClient {

    private static final String PATH = "/gmail-api/messages/enrichment";
    private final RestClient restClient;
    private final String baseUrl;

    public IngesterEnrichmentClient(RestClient restClient, IngesterProperties properties) {
        this.restClient = restClient;
        this.baseUrl = properties.baseUrl();
    }

    public void applyEnrichment(@NonNull String mailboxAddress,
                                @NonNull String messageId,
                                @NonNull String importance,
                                @NonNull String category) {

        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("Ingester base-url is not configured — skipping enrichment call [messageId={}, importance={}, category={}]", messageId, importance, category);
            return;
        }

        Map<String, Object> body = new HashMap<>();
        body.put("messageId", messageId);
        body.put("importance", importance);
        body.put("category", category);

        String uri = UriComponentsBuilder.fromUriString(baseUrl + PATH)
                .queryParam("mailboxAddress", mailboxAddress)
                .build()
                .toUriString();

        try {
            var result = restClient.post()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Enrichment forwarded to ingester [mailbox={}, messageId={}, importance={}, category={}]", mailboxAddress, messageId, importance, category);

            // Add validation logic here
        } catch (Exception e) {
            throw new RuntimeException("Failed to forward enrichment to ingester [messageId=" + messageId + ", importance=" + importance + ", category=" + category + "]", e);
        }
    }
}
