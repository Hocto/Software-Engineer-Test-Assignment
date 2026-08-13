package com.tuum.banking.messaging.event;

import com.tuum.banking.model.enums.Currency;

import java.math.BigDecimal;

/**
 * Emitted whenever a balance row changes. Carries both sides of the change so a
 * consumer can reconcile without querying back.
 */
public record BalanceUpdatedEvent(
        Long balanceId,
        Long accountId,
        Currency currency,
        BigDecimal previousAmount,
        BigDecimal availableAmount
) {
}
