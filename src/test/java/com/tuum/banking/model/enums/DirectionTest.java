package com.tuum.banking.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the arithmetic each direction implies, now that it lives on the enum rather than in a
 * conditional inside {@code TransactionService}.
 */
class DirectionTest {

    @Test
    @DisplayName("IN credits and OUT debits the balance")
    void appliesTheCorrectMovement() {
        BigDecimal balance = new BigDecimal("100.00");

        assertThat(Direction.IN.applyTo(balance, new BigDecimal("25.50"))).isEqualByComparingTo("125.50");
        assertThat(Direction.OUT.applyTo(balance, new BigDecimal("25.50"))).isEqualByComparingTo("74.50");
    }

    @Test
    @DisplayName("OUT may return a negative amount; judging it is the caller's job")
    void doesNotRejectOverdraft() {
        // The enum deliberately has no account context, so it cannot decide what is allowed.
        // TransactionService checks the sign and raises InsufficientFundsException.
        assertThat(Direction.OUT.applyTo(new BigDecimal("10.00"), new BigDecimal("10.01"))).isNegative();
    }

    @ParameterizedTest
    @EnumSource(Direction.class)
    @DisplayName("every direction defines a movement and preserves scale")
    void everyConstantIsUsable(Direction direction) {
        // Guards the reason the operation moved onto the enum: a new constant cannot be added
        // without supplying behaviour, and this fails loudly if one ever is bolted on.
        BigDecimal result = direction.applyTo(new BigDecimal("100.00"), new BigDecimal("1.00"));

        assertThat(result).isNotNull();
        assertThat(result.scale()).isEqualTo(2);
    }
}
