package com.tuum.banking.model.dto;

import com.tuum.banking.model.enums.Currency;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body of {@code POST /accounts}.
 *
 * <p>An unknown value in {@code currencies} fails during JSON binding, which
 * {@code GlobalExceptionHandler} translates into a 400 {@code INVALID_CURRENCY}.
 */
public record CreateAccountRequest(

        @NotNull(message = "customerId is required")
        @Positive(message = "customerId must be positive")
        Long customerId,

        @NotNull(message = "country is required")
        @Size(min = 1, max = 64, message = "country must be between 1 and 64 characters")
        String country,

        @NotEmpty(message = "at least one currency is required")
        List<@NotNull(message = "currency must not be null") Currency> currencies
) {
}
