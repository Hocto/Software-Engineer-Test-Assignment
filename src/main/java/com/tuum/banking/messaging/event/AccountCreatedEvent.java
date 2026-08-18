package com.tuum.banking.messaging.event;

import com.tuum.banking.model.dto.BalanceResponse;
import com.tuum.banking.model.entity.Account;
import com.tuum.banking.model.entity.Balance;

import java.util.List;

public record AccountCreatedEvent(
        Long accountId,
        Long customerId,
        String country,
        List<BalanceResponse> balances
) {

    /**
     * Reuses {@link BalanceResponse} rather than defining a parallel balance shape, so a
     * consumer sees the same JSON for a balance as an API client does.
     */
    public static AccountCreatedEvent from(Account account, List<Balance> balances) {
        return new AccountCreatedEvent(
                account.getId(),
                account.getCustomerId(),
                account.getCountry(),
                balances.stream().map(BalanceResponse::from).toList());
    }
}
