package com.inboxintelligence.processor.config;

import com.inboxintelligence.processor.cleaning.SanitizationStep;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSanitizationPipelineRegistry {

    private final ApplicationContext applicationContext;
    private List<Object> pipeline;

    @PostConstruct
    void buildPipeline() {

        pipeline = applicationContext.getBeansWithAnnotation(SanitizationStep.class)
                .values().stream()
                .sorted(Comparator.comparingInt(bean -> bean.getClass().getAnnotation(SanitizationStep.class).order()))
                .map(bean -> {
                    try {
                        var method = bean.getClass().getMethod("process", String.class);
                        if (method.getReturnType().equals(String.class)) {
                            var annotation = bean.getClass().getAnnotation(SanitizationStep.class);
                            log.info("Step {}: {}", annotation.order(), bean.getClass().getSimpleName());
                            return bean;
                        }
                        throw new IllegalStateException(bean.getClass().getSimpleName() + ": process (String) must return String");
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException(bean.getClass().getSimpleName() + ": must have 'public String clean(String)' method");
                    }
                })
                .toList();

        log.info("Sanitization pipeline built with {} steps", pipeline.size());
    }

    public String execute(String content) {

        if (content == null || content.isBlank()) {
            return content;
        }

        for (Object bean : pipeline) {
            try {
                String before = content;
                content = (String) bean.getClass().getMethod("process", String.class).invoke(bean, content);
                log.debug("{} : {} → {} chars", bean.getClass().getSimpleName(), before.length(), content.length());
            } catch (Exception e) {
                throw new IllegalStateException("Failed at step: " + bean.getClass().getSimpleName(), e);
            }
        }
        return content;
    }


}
