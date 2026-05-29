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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BedrockModelProvider implements ModelProvider {

    private static final ParameterizedTypeReference<Map<String, Object>> CONVERSE_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<Map<String, Object>> EMBED_RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final double DEFAULT_TEMPERATURE = 0.1;
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 1536;
    private static final int MAX_PROMPT_CHARS = 24_000;
    private static final int DEFAULT_EMBEDDING_DIMENSIONS = 1024;
    private static final boolean DEFAULT_EMBEDDING_NORMALIZE = true;

    private final RestClient restClient;
    private final ModelProviderProperties modelProperties;

    @Override
    @Retry(name = "aiRetry")
    public String generate(String prompt) {

        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Bedrock generate called with empty prompt");
        }
        if (prompt.length() > MAX_PROMPT_CHARS) {
            throw new IllegalArgumentException("Bedrock prompt too large: " + prompt.length() + " chars (max " + MAX_PROMPT_CHARS + ")");
        }

        String modelId = modelProperties.bedrock().modelName();
        String url = buildModelUrl(modelId, "converse");

        log.debug("Requesting LLM generation from Bedrock [model={}, promptLength={}]", modelId, prompt.length());

        Map<String, Object> body = Map.of(
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of("text", prompt))
                )),
                "inferenceConfig", Map.of(
                        "temperature", DEFAULT_TEMPERATURE,
                        "maxTokens", DEFAULT_MAX_OUTPUT_TOKENS
                )
        );

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

        String input = text == null ? "" : text;
        if (modelProperties.bedrock().dimensions() != null && input.length() > modelProperties.bedrock().dimensions()) {
            int cutoff = input.lastIndexOf(' ', modelProperties.bedrock().dimensions());
            cutoff = cutoff > 0 ? cutoff : modelProperties.bedrock().dimensions();
            log.warn("Truncating embedding input [{} -> {} chars]", input.length(), cutoff);
            input = input.substring(0, cutoff);
        }

        String modelId = modelProperties.bedrock().modelName();
        String url = buildModelUrl(modelId, "invoke");

        int dimensions = modelProperties.bedrock().dimensions() != null
                ? modelProperties.bedrock().dimensions()
                : DEFAULT_EMBEDDING_DIMENSIONS;
        boolean normalize = modelProperties.bedrock().normalize() != null
                ? modelProperties.bedrock().normalize()
                : DEFAULT_EMBEDDING_NORMALIZE;

        log.debug("Requesting embedding from Bedrock [model={}, textLength={}, dims={}]", modelId, input.length(), dimensions);

        Map<String, Object> body = new HashMap<>();
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

    private String buildModelUrl(String modelId, String op) {
        if (modelId == null || modelId.isBlank()) {
            throw new IllegalStateException("Bedrock model id is not set (check llm-provider.bedrock-model-id and embedding-provider.bedrock-model-id)");
        }
        String base = modelProperties.bedrock().url();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("model-porvider.bedrock.url is not set");
        }
        String trimmedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String encoded = URLEncoder.encode(modelId, StandardCharsets.UTF_8);
        return trimmedBase + "/model/" + encoded + "/" + op;
    }

    private String requireApiKey() {
        String key = modelProperties.bedrock().apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("model-porvider.bedrock.api-key is not set");
        }
        return key;
    }

    private static boolean isTitanV2(String modelId) {
        return modelId != null && modelId.contains("titan-embed-text-v2");
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
