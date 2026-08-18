package com.tuum.banking.model.entity;

import com.tuum.banking.model.enums.Currency;

import java.math.BigDecimal;

/**
 * Persistence view of the {@code balance} table — one row per (account, currency).
 */
public class Balance {

    private Long id;
    private Long accountId;
    private Currency currency;
    private BigDecimal availableAmount;
    private Long version;

    public Balance() {
    }

    public Balance(Long accountId, Currency currency, BigDecimal availableAmount) {
        this.accountId = accountId;
        this.currency = currency;
        this.availableAmount = availableAmount;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public BigDecimal getAvailableAmount() {
        return availableAmount;
    }

    public void setAvailableAmount(BigDecimal availableAmount) {
        this.availableAmount = availableAmount;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
