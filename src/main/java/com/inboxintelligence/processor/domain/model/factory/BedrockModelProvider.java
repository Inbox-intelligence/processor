package com.inboxintelligence.processor.domain.model.factory;

import org.springframework.stereotype.Component;

@Component
public class BedrockModelProvider implements ModelProvider {

    @Override
    public String generate(String prompt) {
        throw new UnsupportedOperationException("BedrockModelProvider is not yet implemented");
    }

    @Override
    public float[] generateEmbedding(String text) {
        throw new UnsupportedOperationException("BedrockModelProvider is not yet implemented");
    }
}
