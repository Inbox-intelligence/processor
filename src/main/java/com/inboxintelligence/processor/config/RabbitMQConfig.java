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

    private final EmailEventRabbitMQProperties properties;

    // --- Shared email-event exchange + DLX ---

    @Bean
    public TopicExchange emailEventExchange() {
        return new TopicExchange(properties.exchange());
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(dlxName());
    }

    // --- Sanitization queue ---

    @Bean
    public Queue emailSanitizationQueue() {
        return buildQueue(properties.sanitizationQueue(), properties.sanitizationRoutingKey());
    }

    @Bean
    public Binding emailSanitizationBinding(Queue emailSanitizationQueue, TopicExchange emailEventExchange) {
        return bindToExchange(emailSanitizationQueue, emailEventExchange, properties.sanitizationRoutingKey());
    }

    @Bean
    public Queue sanitizationDeadLetterQueue() {
        return buildDlq(properties.sanitizationQueue());
    }

    @Bean
    public Binding sanitizationDeadLetterBinding(Queue sanitizationDeadLetterQueue, TopicExchange deadLetterExchange) {
        return bindToExchange(sanitizationDeadLetterQueue, deadLetterExchange, properties.sanitizationRoutingKey() + ".dlq");
    }

    // --- Embedding queue ---

    @Bean
    public Queue emailEmbeddingQueue() {
        return buildQueue(properties.embeddingQueue(), properties.embeddingRoutingKey());
    }

    @Bean
    public Binding emailEmbeddingBinding(Queue emailEmbeddingQueue, TopicExchange emailEventExchange) {
        return bindToExchange(emailEmbeddingQueue, emailEventExchange, properties.embeddingRoutingKey());
    }

    @Bean
    public Queue embeddingDeadLetterQueue() {
        return buildDlq(properties.embeddingQueue());
    }

    @Bean
    public Binding embeddingDeadLetterBinding(Queue embeddingDeadLetterQueue, TopicExchange deadLetterExchange) {
        return bindToExchange(embeddingDeadLetterQueue, deadLetterExchange, properties.embeddingRoutingKey() + ".dlq");
    }

    // --- Clustering queue ---

    @Bean
    public Queue emailClusteringQueue() {
        return buildQueue(properties.clusteringQueue(), properties.clusteringRoutingKey());
    }

    @Bean
    public Binding emailClusteringBinding(Queue emailClusteringQueue, TopicExchange emailEventExchange) {
        return bindToExchange(emailClusteringQueue, emailEventExchange, properties.clusteringRoutingKey());
    }

    @Bean
    public Queue clusteringDeadLetterQueue() {
        return buildDlq(properties.clusteringQueue());
    }

    @Bean
    public Binding clusteringDeadLetterBinding(Queue clusteringDeadLetterQueue, TopicExchange deadLetterExchange) {
        return bindToExchange(clusteringDeadLetterQueue, deadLetterExchange, properties.clusteringRoutingKey() + ".dlq");
    }

    // --- Message converter ---

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    // --- Helpers ---

    private Queue buildQueue(String queueName, String routingKey) {
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", dlxName())
                .withArgument("x-dead-letter-routing-key", routingKey + ".dlq")
                .build();
    }

    private Queue buildDlq(String queueName) {
        return QueueBuilder.durable(queueName + ".dlq").build();
    }

    private Binding bindToExchange(Queue queue, TopicExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }

    private String dlxName() {
        return properties.exchange() + ".dlx";
    }
}
