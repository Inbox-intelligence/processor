package com.inboxintelligence.processor.domain.embedding.factory;

import com.inboxintelligence.processor.config.EmbeddingProviderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaEmbeddingProvider implements EmbeddingProvider {

    private static final ParameterizedTypeReference<Map<String, List<Double>>> RESPONSE_TYPE = new ParameterizedTypeReference<>() {};
    private final RestClient restClient;
    private final EmbeddingProviderProperties properties;

    @Override
    public float[] generateEmbedding(String text) {

        String input = text == null ? "" : text.toLowerCase();
        if (properties.maxChars() != null && input.length() > properties.maxChars()) {
            int cutoff = input.lastIndexOf(' ', properties.maxChars());
            cutoff = cutoff > 0 ? cutoff : properties.maxChars();
            log.warn("Truncating embedding input [{} -> {} chars]", input.length(), cutoff);
            input = input.substring(0, cutoff);
        }

        log.debug("Requesting embedding from Ollama [model={}, textLength={}]", properties.model(), input.length());

        Map<String, List<Double>> response = restClient.post()
                .uri(properties.ollamaUrl())
                .body(Map.of("model", properties.model(), "prompt", input))
                .retrieve()
                .body(RESPONSE_TYPE);

        List<Double> raw = response == null ? null : response.get("embedding");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("Ollama returned no embedding");
        }

        float[] embedding = new float[raw.size()];
        for (int i = 0; i < raw.size(); i++) {
            embedding[i] = raw.get(i).floatValue();
        }

        log.info("Generated embedding [model={}, dimensions={}]", properties.model(), embedding.length);
        return embedding;
    }
}
