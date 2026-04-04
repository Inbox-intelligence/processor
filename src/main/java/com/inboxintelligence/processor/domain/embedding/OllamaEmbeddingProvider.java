package com.inboxintelligence.processor.domain.embedding;

import com.inboxintelligence.processor.config.EmbeddingVendorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private final RestClient restClient;
    private final EmbeddingVendorProperties properties;

    @Override
    public List<Double> generateEmbedding(String text) {

        String input = text;
        if (properties.maxChars() != null && input.length() > properties.maxChars()) {
            log.warn("Truncating embedding input [{} -> {} chars]", input.length(), properties.maxChars());
            input = input.substring(0, properties.maxChars());
        }

        Map<String, Object> request = Map.of(
                "model", properties.model(),
                "prompt", input,
                "options", Map.of("num_ctx", properties.numCtx() != null ? properties.numCtx() : 8192)
        );

        log.debug("Requesting embedding from Ollama [model={}, textLength={}]", properties.model(), input.length());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.post()
                .uri(properties.ollamaUrl())
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("embedding")) {
            throw new IllegalStateException("Ollama returned no embedding");
        }

        @SuppressWarnings("unchecked")
        List<Double> embedding = (List<Double>) response.get("embedding");

        log.info("Generated embedding [model={}, dimensions={}]", properties.model(), embedding.size());
        return embedding;
    }
}
