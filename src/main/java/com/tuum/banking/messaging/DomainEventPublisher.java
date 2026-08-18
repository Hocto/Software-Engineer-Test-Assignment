package com.tuum.banking.messaging;

import com.tuum.banking.messaging.event.EventType;

/**
 * What the domain knows about publishing: an event happened, and it must not leave this
 * process unless the surrounding transaction commits.
 *
 * <p>Deliberately says nothing about RabbitMQ, exchanges or routing keys. Services depend on
 * this and nothing else, so replacing the transport — a Kafka producer, or the transactional
 * outbox the README names as the proper answer to the dual-write problem — changes no service
 * code. That second implementation is a documented likelihood rather than a hypothetical,
 * which is what earns this abstraction its place.
 */
public interface DomainEventPublisher {

    /**
     * Registers an event for publication once the current transaction commits.
     *
     * <p>Call from inside the transaction. Implementations must not deliver anything if that
     * transaction rolls back — a consumer cannot un-see a message about work that never
     * happened.
     */
    void publishAfterCommit(EventType eventType, Object payload);
}
