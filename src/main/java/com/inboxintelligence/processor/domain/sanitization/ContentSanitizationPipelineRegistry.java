package com.inboxintelligence.processor.domain.sanitization;

import com.inboxintelligence.processor.config.SanitizationStep;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSanitizationPipelineRegistry {

    private final ApplicationContext applicationContext;
    private List<Object> sanitizationPipeline;

    @PostConstruct
    void buildSanitizationPipeline() {

        sanitizationPipeline = applicationContext.getBeansWithAnnotation(SanitizationStep.class)
                .values().stream()
                .sorted(Comparator.comparingInt(bean -> bean.getClass().getAnnotation(SanitizationStep.class).order()))
                .peek(bean -> log.info("Preparing SanitizationPipeline Step {}: {}", getSanitizationStepOrder(bean), getSanitizationStepName(bean)))
                .toList();

        log.info("Sanitization pipeline built with {} steps", sanitizationPipeline.size());
    }


    public String executeSanitizationPipeline(String content) {

        for (Object bean : sanitizationPipeline) {
            if (StringUtils.hasText(content)) {
                try {
                    String before = content;
                    content = invokeSanitizationStep(bean, content);
                    log.debug("{} : {} -> {} chars", getSanitizationStepName(bean), before.length(), content.length());
                } catch (Exception e) {
                    throw new IllegalStateException("Failed at step: " + getSanitizationStepName(bean), e);
                }
            }
        }

        return content;
    }

    private String invokeSanitizationStep(Object bean, String content) {

        try {
            var method = bean.getClass().getMethod("process", String.class);
            if (String.class.equals(method.getReturnType())) {
                return (String) method.invoke(bean, content);
            }
            throw new IllegalStateException(getSanitizationStepName(bean) + ": process(String) must return String");
        } catch (Exception e) {
            throw new IllegalStateException(getSanitizationStepName(bean) + ": must have 'public String process(String)' method");
        }
    }

    private String getSanitizationStepName(Object bean) {
        return bean.getClass().getSimpleName();
    }

    private int getSanitizationStepOrder(Object bean) {
        return bean.getClass().getAnnotation(SanitizationStep.class).order();
    }
}
