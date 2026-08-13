package com.tuum.banking.model.dto;

import com.tuum.banking.model.entity.Balance;
import com.tuum.banking.model.enums.Currency;

import java.math.BigDecimal;

public record BalanceResponse(BigDecimal availableAmount, Currency currency) {

    public static BalanceResponse from(Balance balance) {
        return new BalanceResponse(balance.getAvailableAmount(), balance.getCurrency());
    }
}
