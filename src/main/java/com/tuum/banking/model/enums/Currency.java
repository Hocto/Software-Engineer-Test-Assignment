package com.tuum.banking.model.enums;

/**
 * Currencies the service supports. Anything outside this set is rejected with 400.
 */
public enum Currency {
    EUR,
    SEK,
    GBP,
    USD
}
