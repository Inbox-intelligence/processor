package com.inboxintelligence.processor.config;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final EmailProcessingQueueProperties processingProperties;
    private final EmailEmbeddingQueueProperties embeddingProperties;

    // --- Inbound (sanitization queue with DLQ routing) ---

    @Bean
    public Queue emailInboundQueue() {
        return QueueBuilder.durable(processingProperties.queue())
                .withArgument("x-dead-letter-exchange", processingProperties.exchange() + ".dlx")
                .withArgument("x-dead-letter-routing-key", processingProperties.routingKey() + ".dlq")
                .build();
    }

    @Bean
    public TopicExchange emailExchange() {
        return new TopicExchange(processingProperties.exchange());
    }

    @Bean
    public Binding emailInboundBinding(Queue emailInboundQueue, TopicExchange emailExchange) {
        return BindingBuilder
                .bind(emailInboundQueue)
                .to(emailExchange)
                .with(processingProperties.routingKey());
    }

    // --- Embedding (embedding queue with DLQ routing, same exchange) ---

    @Bean
    public Queue emailEmbeddingQueue() {
        return QueueBuilder.durable(embeddingProperties.queue())
                .withArgument("x-dead-letter-exchange", processingProperties.exchange() + ".dlx")
                .withArgument("x-dead-letter-routing-key", embeddingProperties.routingKey() + ".dlq")
                .build();
    }

    @Bean
    public Binding emailEmbeddingBinding(Queue emailEmbeddingQueue, TopicExchange emailExchange) {
        return BindingBuilder
                .bind(emailEmbeddingQueue)
                .to(emailExchange)
                .with(embeddingProperties.routingKey());
    }

    // --- Dead Letter Queue (shared DLX) ---

    @Bean
    public Queue inboundDeadLetterQueue() {
        return QueueBuilder.durable(processingProperties.queue() + ".dlq").build();
    }

    @Bean
    public Queue embeddingDeadLetterQueue() {
        return QueueBuilder.durable(embeddingProperties.queue() + ".dlq").build();
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(processingProperties.exchange() + ".dlx");
    }

    @Bean
    public Binding inboundDeadLetterBinding(Queue inboundDeadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder
                .bind(inboundDeadLetterQueue)
                .to(deadLetterExchange)
                .with(processingProperties.routingKey() + ".dlq");
    }

    @Bean
    public Binding embeddingDeadLetterBinding(Queue embeddingDeadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder
                .bind(embeddingDeadLetterQueue)
                .to(deadLetterExchange)
                .with(embeddingProperties.routingKey() + ".dlq");
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
