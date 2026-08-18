package com.tuum.banking.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology for the {@code banking.events} topic exchange.
 *
 * <p>A topic exchange (rather than direct or fanout) lets new consumers subscribe to
 * slices of the stream — {@code account.#}, or everything with {@code #} — without the
 * publisher changing. Exchange and queues are durable so events survive a broker restart.
 */
@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "banking.events";

    public static final String ACCOUNT_QUEUE = "banking.account.queue";
    public static final String TRANSACTION_QUEUE = "banking.transaction.queue";
    public static final String BALANCE_QUEUE = "banking.balance.queue";

    private static final String ACCOUNT_PATTERN = "account.#";
    private static final String TRANSACTION_PATTERN = "transaction.#";
    private static final String BALANCE_PATTERN = "balance.#";

    @Bean
    public TopicExchange bankingEventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * Queues are declared by the publisher so a fresh {@code docker compose up} has a
     * complete, inspectable topology even before any consumer connects.
     */
    @Bean
    public Declarables bankingQueues(TopicExchange bankingEventsExchange) {
        Queue accountQueue = QueueBuilder.durable(ACCOUNT_QUEUE).build();
        Queue transactionQueue = QueueBuilder.durable(TRANSACTION_QUEUE).build();
        Queue balanceQueue = QueueBuilder.durable(BALANCE_QUEUE).build();

        Binding accountBinding = BindingBuilder.bind(accountQueue).to(bankingEventsExchange).with(ACCOUNT_PATTERN);
        Binding transactionBinding = BindingBuilder.bind(transactionQueue).to(bankingEventsExchange).with(TRANSACTION_PATTERN);
        Binding balanceBinding = BindingBuilder.bind(balanceQueue).to(bankingEventsExchange).with(BALANCE_PATTERN);

        return new Declarables(accountQueue, transactionQueue, balanceQueue,
                accountBinding, transactionBinding, balanceBinding);
    }

    /**
     * Reuses Boot's auto-configured {@link ObjectMapper} so message JSON matches REST
     * JSON exactly — same date format, same null handling.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setExchange(EXCHANGE);
        return template;
    }
}
