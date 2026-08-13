package com.tuum.banking.exception;

/**
 * Stable, machine-readable error identifiers returned in every error body.
 *
 * <p>Clients should branch on these rather than on HTTP status alone or on
 * human-readable messages, which are free to change.
 */
public enum ErrorCode {
    VALIDATION_ERROR,
    INVALID_CURRENCY,
    INVALID_DIRECTION,
    MALFORMED_REQUEST,
    ACCOUNT_NOT_FOUND,
    BALANCE_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    INTERNAL_ERROR
}
