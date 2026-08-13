package com.tuum.banking.messaging.event;

import com.tuum.banking.model.dto.BalanceResponse;

import java.util.List;

public record AccountCreatedEvent(
        Long accountId,
        Long customerId,
        String country,
        List<BalanceResponse> balances
) {
}
