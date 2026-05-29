package com.inboxintelligence.processor.domain.sanitization.pipeline;

import com.inboxintelligence.processor.config.SanitizationStep;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ContentSanitizationPipelineRegistry {

    private final ApplicationContext applicationContext;
    private List<SanitizationStepProcessor> sanitizationPipeline;

    @PostConstruct
    void buildSanitizationPipeline() {

        sanitizationPipeline = applicationContext.getBeansWithAnnotation(SanitizationStep.class)
                .values().stream()
                .filter(bean -> bean instanceof SanitizationStepProcessor)
                .map(bean -> (SanitizationStepProcessor) bean)
                .sorted(Comparator.comparingInt(step -> step.getClass().getAnnotation(SanitizationStep.class).order()))
                .peek(step -> log.info("Preparing SanitizationPipeline Step {}: {}",
                        step.getClass().getAnnotation(SanitizationStep.class).order(),
                        step.getClass().getSimpleName()))
                .toList();

        log.info("Sanitization pipeline built with {} steps", sanitizationPipeline.size());
    }

    public String executeSanitizationPipeline(String content) {

        for (SanitizationStepProcessor step : sanitizationPipeline) {
            if (StringUtils.hasText(content)) {
                try {
                    content = step.process(content);
                } catch (Exception e) {
                    throw new IllegalStateException("Failed at step: " + step.getClass().getSimpleName(), e);
                }
            }
        }
        return content;
    }
}
