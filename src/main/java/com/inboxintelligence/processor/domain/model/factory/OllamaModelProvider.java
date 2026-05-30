package com.inboxintelligence.processor.domain.model.factory;

import com.inboxintelligence.processor.config.ModelProviderProperties;
import com.inboxintelligence.processor.exception.RetryableAIException;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
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

    private static final int MAX_EMBEDDING_INPUT_CHARS = 24_000;

    private final RestClient restClient;
    private final ModelProviderProperties modelProperties;

    @Override
    @Retry(name = "aiRetry")
    public String invokeLlm(String systemPrompt, String prompt) {

        log.debug("Requesting LLM generation from Ollama [model={}, hasSystem={}, promptLength={}]",
                modelProperties.ollama().llm().modelName(),
                systemPrompt != null && !systemPrompt.isBlank(),
                prompt.length());

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", modelProperties.ollama().llm().modelName());
        requestBody.put("prompt", prompt);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            requestBody.put("system", systemPrompt);
        }
        requestBody.put("stream", false);
        requestBody.put("keep_alive", "30s");
        requestBody.put("options", Map.of(
                "temperature", 0.1,
                "num_ctx", 2048));

        try {
            Map<String, Object> response = restClient.post()
                    .uri(modelProperties.ollama().llm().url())
                    .body(requestBody)
                    .retrieve()
                    .body(LLM_RESPONSE_TYPE);

            if (response == null || response.get("response") == null) {
                throw new IllegalStateException("Ollama returned no response for model=" + modelProperties.ollama().llm().modelName());
            }

            String text = response.get("response").toString();
            log.debug("LLM generation completed [model={}, responseLength={}]", modelProperties.ollama().llm().modelName(), text.length());
            return text;

        } catch (ResourceAccessException e) {
            throw new RetryableAIException("Ollama generate I/O failure: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableAIException("Ollama generate returned " + e.getStatusCode(), e);
        }
    }

    @Override
    @Retry(name = "aiRetry")
    public float[] generateEmbedding(String text) {

        String input = text == null ? "" : text;
        if (input.length() > MAX_EMBEDDING_INPUT_CHARS) {
            int cutoff = input.lastIndexOf(' ', MAX_EMBEDDING_INPUT_CHARS);
            cutoff = cutoff > 0 ? cutoff : MAX_EMBEDDING_INPUT_CHARS;
            log.warn("Truncating embedding input [{} -> {} chars]", input.length(), cutoff);
            input = input.substring(0, cutoff);
        }

        log.debug("Requesting embedding from Ollama [model={}, textLength={}]", modelProperties.ollama().embedding().modelName(), input.length());

        try {
            Map<String, List<Double>> response = restClient.post()
                    .uri(modelProperties.ollama().embedding().url())
                    .body(Map.of(
                            "model", modelProperties.ollama().embedding().modelName(),
                            "prompt", input,
                            "keep_alive", "30s"))
                    .retrieve()
                    .body(EMBEDDING_RESPONSE_TYPE);

            List<Double> raw = response == null ? null : response.get("embedding");
            if (raw == null || raw.isEmpty()) {
                throw new IllegalStateException("Ollama returned no embedding for model=" + modelProperties.ollama().embedding().modelName());
            }

            float[] embedding = new float[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                embedding[i] = raw.get(i).floatValue();
            }

            log.debug("Generated embedding [model={}, dimensions={}]", modelProperties.ollama().embedding().modelName(), embedding.length);
            return embedding;

        } catch (ResourceAccessException e) {
            throw new RetryableAIException("Ollama embedding I/O failure: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            String body = e.getResponseBodyAsString();
            if (body != null && body.contains("input length exceeds the context length")) {
                log.error("Ollama embedding rejected input — too long for model context [textLength={}, model={}]",
                        input.length(), modelProperties.ollama().embedding().modelName());
                throw new IllegalStateException("Embedding input exceeds model context window: " + body, e);
            }
            throw new RetryableAIException("Ollama embedding returned " + e.getStatusCode(), e);
        }
    }
}
