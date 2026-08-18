package com.tuum.banking.messaging;

import com.tuum.banking.messaging.event.EventEnvelope;
import com.tuum.banking.messaging.event.EventType;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Wraps the payload in an {@link EventEnvelope} and hands it to Spring's application event
 * machinery, which holds it until the current transaction commits.
 *
 * <p>This class deliberately knows no transport. It is the domain-facing half of publishing;
 * {@link RabbitEventRelay} is the half that talks to a broker. Keeping them apart is what
 * lets the transport change without any service noticing.
 */
@Component
public class AfterCommitEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public AfterCommitEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publishAfterCommit(EventType eventType, Object payload) {
        applicationEventPublisher.publishEvent(EventEnvelope.of(eventType, payload));
    }
}
