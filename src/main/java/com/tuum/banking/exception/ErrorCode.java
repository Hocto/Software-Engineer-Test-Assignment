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
    /**
     * The currency is not one this service supports at all. Absolute, and independent of
     * any account — retrying the same request can never succeed.
     *
     * <p>Named "unsupported" rather than "invalid" because the assignment's single
     * "Invalid currency" error covers two distinct situations; see {@link #CURRENCY_NOT_HELD}.
     * Reusing the specification's exact term for one of them would misdirect anyone mapping
     * requirements to code.
     */
    UNSUPPORTED_CURRENCY,
    INVALID_DIRECTION,
    MALFORMED_REQUEST,

    // Protocol-level failures, raised by Spring MVC before a controller is reached
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    NOT_ACCEPTABLE,
    NOT_FOUND,

    // Domain failures
    ACCOUNT_NOT_FOUND,
    /**
     * The currency is supported by the service, but this account holds no balance in it.
     * Contextual rather than absolute: the same request could succeed once the account
     * gains that balance, so a client may retry after remediating.
     */
    CURRENCY_NOT_HELD,
    INSUFFICIENT_FUNDS,

    INTERNAL_ERROR
}
