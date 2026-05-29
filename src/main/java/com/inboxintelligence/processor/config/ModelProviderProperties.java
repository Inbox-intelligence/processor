package com.inboxintelligence.processor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "model-porvider")
public record ModelProviderProperties(
        String embeddingProviderName,
        String llmProviderName,
        Ollama ollama,
        Bedrock bedrock
) {

    public record Ollama(
            Embedding embedding,
            Llm llm
    ) {
        public record Embedding(
                String url,
                String modelName
        ) {
        }

        public record Llm(
                String url,
                String modelName
        ) {
        }
    }

    public record Bedrock(
            String url,
            String apiKey,
            String modelName,
            Integer dimensions,
            Boolean normalize
    ) {
    }
}
