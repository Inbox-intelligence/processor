package com.inboxintelligence.processor.domain.model.factory;

import com.inboxintelligence.processor.config.LlmProviderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class ModelProviderFactory {

    private static final String DEFAULT_PROVIDER_BEAN = "ollamaModelProvider";

    private final ModelProvider activeProvider;

    public ModelProviderFactory(LlmProviderProperties properties, Map<String, ModelProvider> modelProviderBeanMap) {

        String beanName = DEFAULT_PROVIDER_BEAN;
        if (StringUtils.hasText(properties.name())) {
            beanName = properties.name().toLowerCase(Locale.ROOT) + "ModelProvider";
        }

        ModelProvider provider = modelProviderBeanMap.get(beanName);

        if (provider == null) {
            log.warn("Model provider '{}' not found. Falling back to default: {}", beanName, DEFAULT_PROVIDER_BEAN);
            provider = modelProviderBeanMap.get(DEFAULT_PROVIDER_BEAN);
        }

        if (provider == null) {
            throw new IllegalStateException("No ModelProvider available. Configured: '%s'. Available: %s".formatted(properties.name(), modelProviderBeanMap.keySet()));
        }

        this.activeProvider = provider;
        log.info("Active ModelProvider: {}", provider.getClass().getSimpleName());
    }

    public ModelProvider getProvider() {
        return activeProvider;
    }
}
