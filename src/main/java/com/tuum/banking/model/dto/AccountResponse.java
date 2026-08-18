package com.tuum.banking.model.dto;

import com.tuum.banking.model.entity.Account;
import com.tuum.banking.model.entity.Balance;

import java.util.List;

public record AccountResponse(
        Long accountId,
        Long customerId,
        String country,
        List<BalanceResponse> balances
) {

    public static AccountResponse from(Account account, List<Balance> balances) {
        return new AccountResponse(
                account.getId(),
                account.getCustomerId(),
                account.getCountry(),
                balances.stream().map(BalanceResponse::from).toList()
        );
    }
}
