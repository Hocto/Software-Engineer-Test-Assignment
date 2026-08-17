package com.tuum.banking.messaging.event;

import com.tuum.banking.model.entity.Balance;
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

    /**
     * @param balance the row as it was read under the lock, before the update
     */
    public static BalanceUpdatedEvent of(Balance balance, BigDecimal previousAmount, BigDecimal availableAmount) {
        return new BalanceUpdatedEvent(
                balance.getId(),
                balance.getAccountId(),
                balance.getCurrency(),
                previousAmount,
                availableAmount);
    }
}
