package com.inboxintelligence.processor.domain.model.factory;

import com.inboxintelligence.processor.config.EmbeddingProviderProperties;
import com.inboxintelligence.processor.config.LlmProviderProperties;
import com.inboxintelligence.processor.exception.RetryableOllamaException;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OllamaModelProvider implements ModelProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> LLM_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, List<Double>>> EMBEDDING_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private final RestClient restClient;
    private final LlmProviderProperties llmProperties;
    private final EmbeddingProviderProperties embeddingProperties;

    @Override
    @Retry(name = "ollamaRetry")
    public String generate(String prompt) {

        log.debug("Requesting LLM generation from Ollama [model={}, promptLength={}]", llmProperties.model(), prompt.length());

        try {
            Map<String, Object> response = restClient.post()
                    .uri(llmProperties.ollamaUrl())
                    .body(Map.of(
                            "model", llmProperties.model(),
                            "prompt", prompt,
                            "stream", false,
                            "keep_alive", "30s",
                            "options", Map.of(
                                    "temperature", 0.1,
                                    "num_ctx", 2048)))
                    .retrieve()
                    .body(LLM_RESPONSE_TYPE);

            if (response == null || response.get("response") == null) {
                throw new IllegalStateException("Ollama returned no response for model=" + llmProperties.model());
            }

            String text = response.get("response").toString();
            log.debug("LLM generation completed [model={}, responseLength={}]", llmProperties.model(), text.length());
            return text;

        } catch (ResourceAccessException e) {
            throw new RetryableOllamaException("Ollama generate I/O failure: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableOllamaException("Ollama generate returned " + e.getStatusCode(), e);
        }
    }

    @Override
    @Retry(name = "ollamaRetry")
    public float[] generateEmbedding(String text) {

        String input = text == null ? "" : text;
        if (embeddingProperties.maxChars() != null && input.length() > embeddingProperties.maxChars()) {
            int cutoff = input.lastIndexOf(' ', embeddingProperties.maxChars());
            cutoff = cutoff > 0 ? cutoff : embeddingProperties.maxChars();
            log.warn("Truncating embedding input [{} -> {} chars]", input.length(), cutoff);
            input = input.substring(0, cutoff);
        }

        log.debug("Requesting embedding from Ollama [model={}, textLength={}]", embeddingProperties.model(), input.length());

        try {
            Map<String, List<Double>> response = restClient.post()
                    .uri(embeddingProperties.ollamaUrl())
                    .body(Map.of(
                            "model", embeddingProperties.model(),
                            "prompt", input,
                            "keep_alive", "30s"))
                    .retrieve()
                    .body(EMBEDDING_RESPONSE_TYPE);

            List<Double> raw = response == null ? null : response.get("embedding");
            if (raw == null || raw.isEmpty()) {
                throw new IllegalStateException("Ollama returned no embedding for model=" + embeddingProperties.model());
            }

            float[] embedding = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                embedding[i] = raw.get(i).floatValue();
            }

            log.info("Generated embedding [model={}, dimensions={}]", embeddingProperties.model(), embedding.length);
            return embedding;

        } catch (ResourceAccessException e) {
            throw new RetryableOllamaException("Ollama embedding I/O failure: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            String body = e.getResponseBodyAsString();
            if (body != null && body.contains("input length exceeds the context length")) {
                log.error("Ollama embedding rejected input — too long for model context [textLength={}, model={}]",
                        input.length(), embeddingProperties.model());
                throw new IllegalStateException("Embedding input exceeds model context window: " + body, e);
            }
            throw new RetryableOllamaException("Ollama embedding returned " + e.getStatusCode(), e);
        }
    }
}
