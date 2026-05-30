package com.inboxintelligence.processor.domain.model.factory;

import com.inboxintelligence.processor.config.ModelProviderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class ModelProviderFactory {

    private static final String DEFAULT_PROVIDER_BEAN = "ollamaModelProvider";

    private final ModelProvider llmProvider;
    private final ModelProvider embeddingProvider;

    public ModelProviderFactory(ModelProviderProperties properties, Map<String, ModelProvider> modelProviderBeanMap) {
        this.llmProvider = resolve("llm", properties.llmProviderName(), modelProviderBeanMap);
        this.embeddingProvider = resolve("embedding", properties.embeddingProviderName(), modelProviderBeanMap);

        log.info("Active LLM provider: {} | embedding provider: {}",
                llmProvider.getClass().getSimpleName(),
                embeddingProvider.getClass().getSimpleName());
    }

    private static ModelProvider resolve(String role, String configuredName, Map<String, ModelProvider> beans) {
        String beanName = DEFAULT_PROVIDER_BEAN;
        if (StringUtils.hasText(configuredName)) {
            beanName = configuredName.toLowerCase(Locale.ROOT) + "ModelProvider";
        }

        ModelProvider provider = beans.get(beanName);
        if (provider == null) {
            log.warn("Model provider '{}' for role '{}' not found. Falling back to default: {}", beanName, role, DEFAULT_PROVIDER_BEAN);
            provider = beans.get(DEFAULT_PROVIDER_BEAN);
        }
        if (provider == null) {
            throw new IllegalStateException("No ModelProvider available for role '%s'. Configured: '%s'. Available: %s"
                    .formatted(role, configuredName, beans.keySet()));
        }
        return provider;
    }

    public ModelProvider getLlmProvider() {
        return llmProvider;
    }

    public ModelProvider getEmbeddingProvider() {
        return embeddingProvider;
    }
}
