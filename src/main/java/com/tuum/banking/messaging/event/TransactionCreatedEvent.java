package com.tuum.banking.messaging.event;

import com.tuum.banking.model.entity.Transaction;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;

import java.math.BigDecimal;

public record TransactionCreatedEvent(
        Long transactionId,
        Long accountId,
        BigDecimal amount,
        Currency currency,
        Direction direction,
        String description,
        BigDecimal balanceAfter
) {

    /**
     * Built from the persisted row, so the event always reports what was actually stored —
     * including the database-generated id, which only exists after the insert.
     */
    public static TransactionCreatedEvent from(Transaction transaction) {
        return new TransactionCreatedEvent(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDirection(),
                transaction.getDescription(),
                transaction.getBalanceAfter());
    }
}
