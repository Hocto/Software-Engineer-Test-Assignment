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

    public InsufficientFundsException(Long accountId, Currency currency,
                                      BigDecimal available, BigDecimal requested) {
        super(ErrorCode.INSUFFICIENT_FUNDS, HttpStatus.UNPROCESSABLE_ENTITY,
                "Insufficient funds on account %d: %s %s available, %s requested"
                        .formatted(accountId, available, currency, requested));
    }
}
