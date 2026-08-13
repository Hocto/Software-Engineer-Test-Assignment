package com.tuum.banking.model.dto;

import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body of {@code POST /accounts/{accountId}/transactions}.
 *
 * <p>{@code @Digits} matches the {@code NUMERIC(19,4)} column so excess precision is
 * rejected explicitly rather than silently rounded by the database.
 */
public record CreateTransactionRequest(

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = 4, message = "amount supports at most 4 decimal places")
        BigDecimal amount,

        @NotNull(message = "currency is required")
        Currency currency,

        @NotNull(message = "direction is required")
        Direction direction,

        @NotBlank(message = "description is required")
        @Size(max = 1000, message = "description must be at most 1000 characters")
        String description
) {
}
