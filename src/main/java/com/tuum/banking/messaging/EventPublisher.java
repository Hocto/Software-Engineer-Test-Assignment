package com.tuum.banking.messaging;

import com.tuum.banking.config.RabbitMqConfig;
import com.tuum.banking.messaging.event.EventEnvelope;
import com.tuum.banking.messaging.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Relays domain events to RabbitMQ <strong>after</strong> the originating database
 * transaction commits.
 *
 * <p>This is the central messaging decision. Publishing inline with the write would
 * emit events for work that later rolls back — an OUT transaction rejected for
 * insufficient funds, or any failure between the balance update and the commit — and
 * consumers have no way to un-see a message. Deferring to {@code AFTER_COMMIT} means a
 * published event always corresponds to committed state.
 *
 * <p>The tradeoff is the reverse failure mode: a broker outage in the window after
 * commit loses the event, since the database work is already durable. That is the
 * standard dual-write problem, and closing it properly needs a transactional outbox —
 * see the README's scaling section. For this service, at-most-once delivery of a
 * notification is the right side of the tradeoff, because the database, not the event
 * stream, is the system of record.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;

    public EventPublisher(RabbitTemplate rabbitTemplate, ApplicationEventPublisher applicationEventPublisher) {
        this.rabbitTemplate = rabbitTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    /**
     * Registers an event for publication once the current transaction commits.
     * Called from services while still inside the transaction.
     */
    public void publishAfterCommit(EventType eventType, Object payload) {
        applicationEventPublisher.publishEvent(EventEnvelope.of(eventType, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(EventEnvelope<?> envelope) {
        send(envelope);
    }

    private void send(EventEnvelope<?> envelope) {
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.EXCHANGE, envelope.eventType().routingKey(), envelope);
            log.debug("Published {} ({}) to {}", envelope.eventType(), envelope.eventId(), RabbitMqConfig.EXCHANGE);
        } catch (AmqpException ex) {
            // The transaction is already committed; rethrowing would fail the HTTP response
            // for work that actually succeeded. Log loudly and let the caller see its 2xx.
            log.error("Failed to publish {} ({}); database state is committed and the event is lost",
                    envelope.eventType(), envelope.eventId(), ex);
        }
    }
}
