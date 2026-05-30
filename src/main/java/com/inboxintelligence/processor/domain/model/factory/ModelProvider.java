package com.inboxintelligence.processor.domain.model.factory;

public interface ModelProvider {

    String invokeLlm(String systemPrompt, String prompt);

    float[] generateEmbedding(String text);
}
