package com.tuum.banking.exception;

/**
 * Stable, machine-readable error identifiers returned in every error body.
 *
 * <p>Clients should branch on these rather than on HTTP status alone or on
 * human-readable messages, which are free to change.
 */
public enum ErrorCode {

    // Request-level failures
    VALIDATION_ERROR,
    INVALID_CURRENCY,
    INVALID_DIRECTION,
    MALFORMED_REQUEST,

    // Protocol-level failures, raised by Spring MVC before a controller is reached
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    NOT_ACCEPTABLE,
    NOT_FOUND,

    // Domain failures
    ACCOUNT_NOT_FOUND,
    BALANCE_NOT_FOUND,
    INSUFFICIENT_FUNDS,

    INTERNAL_ERROR
}
