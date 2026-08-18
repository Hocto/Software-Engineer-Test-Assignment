package com.tuum.banking.exception;

import com.tuum.banking.model.enums.Currency;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * An OUT transaction would take the balance below zero.
 *
 * <p>422 rather than 400: the request is syntactically valid and the account exists,
 * but a business rule forbids it. That distinction lets clients tell "you sent me
 * something malformed, fix the request" apart from "the request was fine, the account
 * state does not permit it".
 */
public class InsufficientFundsException extends BusinessException {

    /**
     * The message deliberately omits the available balance and reports only what the caller
     * already supplied — account, currency, amount. Echoing the balance would turn a rejected
     * withdrawal into a balance-disclosure oracle: anyone able to reach this endpoint could
     * binary-search an account's funds without ever reading it. That is harmless while the API
     * is unauthenticated and {@code GET /accounts/{id}} exposes the same figure, but it is the
     * wrong default to leave in place for whenever authentication arrives.
     *
     * <p>Operators lose nothing: the account is named, and its balance is one query away.
     */
    public InsufficientFundsException(Long accountId, Currency currency, BigDecimal requested) {
        super(ErrorCode.INSUFFICIENT_FUNDS, HttpStatus.UNPROCESSABLE_ENTITY,
                "Insufficient funds on account %d to withdraw %s %s"
                        .formatted(accountId, requested, currency));
    }
}
