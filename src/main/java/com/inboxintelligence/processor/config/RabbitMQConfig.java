package com.inboxintelligence.processor.config;

import com.inboxintelligence.persistence.config.RabbitMQProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RabbitMQConfig {

    private final RabbitMQProperties properties;

    // --- Inbound (main queue with DLQ routing) ---

    @Bean
    public Queue emailInboundQueue() {
        return QueueBuilder.durable(properties.queue())
                .withArgument("x-dead-letter-exchange", properties.exchange() + ".dlx")
                .withArgument("x-dead-letter-routing-key", properties.routingKey() + ".dlq")
                .build();
    }

    @Bean
    public TopicExchange emailInboundExchange() {
        return new TopicExchange(properties.exchange());
    }

    @Bean
    public Binding emailInboundBinding(Queue emailInboundQueue, TopicExchange emailInboundExchange) {
        return BindingBuilder
                .bind(emailInboundQueue)
                .to(emailInboundExchange)
                .with(properties.routingKey());
    }

    // --- Dead Letter Queue ---

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(properties.queue() + ".dlq").build();
    }

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange(properties.exchange() + ".dlx");
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder
                .bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(properties.routingKey() + ".dlq");
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
