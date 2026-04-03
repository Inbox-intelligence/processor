package com.inboxintelligence.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = "com.inboxintelligence")
@ConfigurationPropertiesScan(basePackages = "com.inboxintelligence")
@EntityScan(basePackages = "com.inboxintelligence")
@EnableJpaRepositories(basePackages = "com.inboxintelligence")
public class ProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProcessorApplication.class, args);
    }
}
