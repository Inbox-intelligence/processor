package com.inboxintelligence.processor.domain.model.factory;

public interface ModelProvider {

    String generate(String prompt);

    float[] generateEmbedding(String text);
}
