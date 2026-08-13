package com.tuum.banking.model.dto;

import com.tuum.banking.model.enums.Currency;
import jakarta.validation.constraints.NotBlank;
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

        @NotBlank(message = "country is required")
        @Size(max = 64, message = "country must be at most 64 characters")
        String country,

        @NotEmpty(message = "at least one currency is required")
        List<@NotNull(message = "currency must not be null") Currency> currencies
) {

    /**
     * Trims {@code country} before validation runs.
     *
     * <p>Ordering is what makes this work: Jackson constructs the record first, so a
     * whitespace-only {@code "   "} collapses to {@code ""} and is then rejected by
     * {@code @NotBlank}. It also keeps padded input from reaching the database.
     */
    public CreateAccountRequest {
        if (country != null) {
            country = country.trim();
        }
    }
}
