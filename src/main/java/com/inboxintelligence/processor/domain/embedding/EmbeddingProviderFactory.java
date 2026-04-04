package com.inboxintelligence.processor.domain.embedding;

import com.inboxintelligence.processor.config.EmbeddingVendorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class EmbeddingProviderFactory {

    private static final String DEFAULT_PROVIDER_BEAN = "ollamaEmbeddingProvider";

    private final EmbeddingProvider activeEmbeddingProvider;

    public EmbeddingProviderFactory(EmbeddingVendorProperties embeddingVendorProperties,
                                    Map<String, EmbeddingProvider> embeddingProviderBeanMap) {

        String configuredProvider = embeddingVendorProperties.provider();
        String beanName = DEFAULT_PROVIDER_BEAN;

        if (StringUtils.hasText(configuredProvider)) {
            beanName = configuredProvider.toLowerCase(Locale.ROOT) + "EmbeddingProvider";
        }

        EmbeddingProvider provider = embeddingProviderBeanMap.get(beanName);

        if (provider == null) {
            log.warn("Embedding provider '{}' not found. Falling back to default: {}", beanName, DEFAULT_PROVIDER_BEAN);
            provider = embeddingProviderBeanMap.get(DEFAULT_PROVIDER_BEAN);
        }

        this.activeEmbeddingProvider = provider;
        log.info("Active EmbeddingProvider: {}", provider.getClass().getSimpleName());
    }

    public EmbeddingProvider getProvider() {
        return activeEmbeddingProvider;
    }
}
