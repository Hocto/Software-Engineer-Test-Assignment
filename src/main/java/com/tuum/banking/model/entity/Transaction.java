package com.tuum.banking.model.entity;

import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Persistence view of the {@code transaction} table.
 *
 * <p>{@code balanceAfter} is captured at write time so the transaction history is a
 * self-contained ledger and does not have to be replayed to answer "what was the
 * balance after this movement?".
 */
public class Transaction {

    private Long id;
    private Long accountId;
    private BigDecimal amount;
    private Currency currency;
    private Direction direction;
    private String description;
    private BigDecimal balanceAfter;
    private OffsetDateTime createdAt;

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

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Currency getCurrency() {
        return currency;
    }

    public void setCurrency(Currency currency) {
        this.currency = currency;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public void setBalanceAfter(BigDecimal balanceAfter) {
        this.balanceAfter = balanceAfter;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
