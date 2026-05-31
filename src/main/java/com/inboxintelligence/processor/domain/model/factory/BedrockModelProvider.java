package com.inboxintelligence.processor.domain.model.factory;

import com.inboxintelligence.processor.config.ModelProviderProperties;
import com.inboxintelligence.processor.exception.RetryableAIException;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BedrockModelProvider implements ModelProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> CONVERSE_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, Object>> EMBED_RESPONSE_TYPE = new ParameterizedTypeReference<>() {
    };

    private static final double DEFAULT_TEMPERATURE = 0.0;
    private static final double DEFAULT_TOP_P = 1.0;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 512;
    private static final int MAX_PROMPT_CHARS = 24_000;
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1024;
    private static final boolean DEFAULT_EMBEDDING_NORMALIZE = true;

    private final RestClient restClient;
    private final ModelProviderProperties modelProperties;

    private static boolean isTitanV2(String modelId) {
        return modelId != null && modelId.contains("titan-embed-text-v2");
    }

    @Override
    @Retry(name = "aiRetry")
    public String invokeLlm(String systemPrompt, String prompt) {

        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Bedrock invokeLlm called with empty prompt");
        }
        if (prompt.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException("Bedrock prompt too large: " + prompt.length() + " chars (max " + MAX_PROMPT_CHARS + ")");
        }

        String modelId = modelProperties.bedrock().llm().modelName();
        URI url = buildModelUri(modelId, "converse");

        log.debug("Requesting LLM generation from Bedrock [model={}, hasSystem={}, promptLength={}]",
                modelId,
                systemPrompt != null && !systemPrompt.isBlank(),
                prompt.length());

        Map<String, Object> body = new LinkedHashMap<>();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            body.put("system", List.of(Map.of("text", systemPrompt)));
        }
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", List.of(Map.of("text", prompt))
        )));
        body.put("inferenceConfig", Map.of(
                "temperature", DEFAULT_TEMPERATURE,
                "topP", DEFAULT_TOP_P,
                "maxTokens", DEFAULT_MAX_OUTPUT_TOKENS
        ));

        try {
            Map<String, Object> response = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + requireApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(body)
                    .retrieve()
                    .body(CONVERSE_RESPONSE_TYPE);

            String text = extractConverseText(response, modelId);
            logUsage(response, modelId, text.length());
            return text;

        } catch (ResourceAccessException e) {
            throw new RetryableAIException("Bedrock generate I/O failure: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableAIException("Bedrock generate returned " + e.getStatusCode(), e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryableAIException("Bedrock throttled (429): " + e.getResponseBodyAsString(), e);
        } catch (HttpClientErrorException e) {
            log.error("Bedrock rejected generate request [{}]: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Bedrock generate failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    @Override
    @Retry(name = "aiRetry")
    public float[] generateEmbedding(String text) {

        ModelProviderProperties.Bedrock.Embedding embeddingProps = modelProperties.bedrock().embedding();

        String input = text == null ? "" : text;
        if (embeddingProps.dimensions() != null && input.length() > embeddingProps.dimensions()) {
            int cutoff = input.lastIndexOf(' ', embeddingProps.dimensions());
            cutoff = cutoff > 0 ? cutoff : embeddingProps.dimensions();
            log.warn("Truncating embedding input [{} -> {} chars]", input.length(), cutoff);
            input = input.substring(0, cutoff);
        }

        String modelId = embeddingProps.modelName();
        URI url = buildModelUri(modelId, "invoke");

        int dimensions = embeddingProps.dimensions() != null
                ? embeddingProps.dimensions()
                : DEFAULT_EMBEDDING_DIMENSIONS;
        boolean normalize = embeddingProps.normalize() != null
                ? embeddingProps.normalize()
                : DEFAULT_EMBEDDING_NORMALIZE;

        log.debug("Requesting embedding from Bedrock [model={}, textLength={}, dims={}]",
                modelId,
                input.length(),
                dimensions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("inputText", input);
        if (isTitanV2(modelId)) {
            body.put("dimensions", dimensions);
            body.put("normalize", normalize);
        }

        try {
            Map<String, Object> response = restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + requireApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(body)
                    .retrieve()
                    .body(EMBED_RESPONSE_TYPE);

            return extractEmbedding(response, modelId);

        } catch (ResourceAccessException e) {
            throw new RetryableAIException("Bedrock embedding I/O failure: " + e.getMessage(), e);
        } catch (HttpServerErrorException e) {
            throw new RetryableAIException("Bedrock embedding returned " + e.getStatusCode(), e);
        } catch (HttpClientErrorException.TooManyRequests e) {
            throw new RetryableAIException("Bedrock embedding throttled (429): " + e.getResponseBodyAsString(), e);
        } catch (HttpClientErrorException e) {
            log.error("Bedrock rejected embedding request [{}]: {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new IllegalStateException("Bedrock embedding failed: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    private URI buildModelUri(String modelId, String op) {
        if (modelId == null || modelId.isBlank() || modelId.contains("${")) {
            throw new IllegalStateException("Bedrock model id is not set or unresolved (got '" + modelId + "'). Check BEDROCK_LLM_MODEL_NAME / BEDROCK_EMBEDDING_MODEL_NAME env vars.");
        }
        String base = modelProperties.bedrock().url();
        if (base == null || base.isBlank() || base.contains("${")) {
            throw new IllegalStateException("Bedrock base URL is not set or unresolved (got '" + base + "'). Check BEDROCK_URL env var.");
        }
        String trimmedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(trimmedBase + "/model/" + modelId + "/" + op);
    }

    private String requireApiKey() {
        String key = modelProperties.bedrock().apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("model-porvider.bedrock.api-key is not set");
        }
        return key;
    }

    @SuppressWarnings("unchecked")
    private String extractConverseText(Map<String, Object> response, String modelId) {
        if (response == null)
            throw new IllegalStateException("Bedrock Converse returned empty body for model=" + modelId);
        Map<String, Object> output = (Map<String, Object>) response.get("output");
        if (output == null)
            throw new IllegalStateException("Bedrock response missing 'output' for model=" + modelId + ": " + response);
        Map<String, Object> message = (Map<String, Object>) output.get("message");
        if (message == null)
            throw new IllegalStateException("Bedrock response missing 'output.message' for model=" + modelId + ": " + response);
        List<Map<String, Object>> content = (List<Map<String, Object>>) message.get("content");
        if (content == null || content.isEmpty())
            throw new IllegalStateException("Bedrock response missing 'output.message.content' for model=" + modelId + ": " + response);
        for (Map<String, Object> block : content) {
            Object text = block.get("text");
            if (text != null) return text.toString();
        }
        throw new IllegalStateException("Bedrock response has no text content block for model=" + modelId + ": " + response);
    }

    @SuppressWarnings("unchecked")
    private float[] extractEmbedding(Map<String, Object> response, String modelId) {
        if (response == null)
            throw new IllegalStateException("Bedrock embedding returned empty body for model=" + modelId);
        Object raw = response.get("embedding");
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new IllegalStateException("Bedrock embedding response missing 'embedding' for model=" + modelId + ": " + response);
        float[] embedding = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            embedding[i] = (v instanceof Number n) ? n.floatValue() : 0f;
        }
        Object tokens = response.get("inputTextTokenCount");
        log.debug("Generated embedding [model={}, dimensions={}, inputTokens={}]", modelId, embedding.length, tokens);
        return embedding;
    }

    @SuppressWarnings("unchecked")
    private void logUsage(Map<String, Object> response, String modelId, int responseLength) {
        Object usageObj = response.get("usage");
        if (usageObj instanceof Map<?, ?> usage) {
            log.debug("LLM generation completed [model={}, responseLength={}, inputTokens={}, outputTokens={}, totalTokens={}]",
                    modelId,
                    responseLength,
                    ((Map<String, Object>) usage).get("inputTokens"),
                    ((Map<String, Object>) usage).get("outputTokens"),
                    ((Map<String, Object>) usage).get("totalTokens"));
        } else {
            log.debug("LLM generation completed [model={}, responseLength={}]", modelId, responseLength);
        }
    }
}
