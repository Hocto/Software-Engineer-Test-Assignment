package com.tuum.banking.model.dto;

import com.tuum.banking.model.entity.Transaction;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;

import java.math.BigDecimal;

public record TransactionResponse(
        Long transactionId,
        Long accountId,
        BigDecimal amount,
        Currency currency,
        Direction direction,
        String description,
        BigDecimal balanceAfter
) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDirection(),
                transaction.getDescription(),
                transaction.getBalanceAfter()
        );
    }
}
