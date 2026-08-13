package com.tuum.banking.messaging.event;

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
}
