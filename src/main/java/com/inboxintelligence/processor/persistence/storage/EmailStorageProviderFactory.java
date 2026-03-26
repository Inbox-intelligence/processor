package com.inboxintelligence.processor.persistence.storage;

import com.inboxintelligence.processor.config.EmailStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

@Component
@Slf4j
public class EmailStorageProviderFactory {

    private static final String DEFAULT_PROVIDER_BEAN = "localEmailStorageProvider";

    private final EmailStorageProvider activeProvider;

    public EmailStorageProviderFactory(EmailStorageProperties properties, Map<String, EmailStorageProvider> providerBeanMap) {

        String configuredProvider = properties.provider();
        String beanName = DEFAULT_PROVIDER_BEAN;

        if (StringUtils.hasText(configuredProvider)) {
            beanName = configuredProvider.toLowerCase(Locale.ROOT) + "EmailStorageProvider";
        }

        EmailStorageProvider provider = providerBeanMap.get(beanName);

        if (provider == null) {
            log.warn("Email storage provider '{}' not found. Falling back to LOCAL provider.", beanName);
            provider = providerBeanMap.get(DEFAULT_PROVIDER_BEAN);
        }

        this.activeProvider = provider;
        log.info("Active EmailStorageProvider: {}", provider.getClass().getSimpleName());
    }

    public EmailStorageProvider getProvider() {
        return activeProvider;
    }
}
