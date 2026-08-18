package com.tuum.banking.messaging.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire format for every message on {@code banking.events}.
 *
 * <p>The envelope is deliberately uniform so a consumer can route on {@code eventType}
 * and deduplicate on {@code eventId} without knowing any payload schema.
 *
 * @param eventId    unique per publish; a consumer's deduplication key
 * @param eventType  discriminator for {@code payload}
 * @param occurredAt when the originating transaction committed
 * @param payload    event-specific body
 */
public record EventEnvelope<T>(
        String eventId,
        EventType eventType,
        OffsetDateTime occurredAt,
        T payload
) {

    public static <T> EventEnvelope<T> of(EventType eventType, T payload) {
        return new EventEnvelope<>(UUID.randomUUID().toString(), eventType, OffsetDateTime.now(), payload);
    }
}
