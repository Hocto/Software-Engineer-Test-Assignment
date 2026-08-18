package com.tuum.banking.exception;

import com.tuum.banking.model.enums.Currency;
import org.springframework.http.HttpStatus;

/**
 * The account exists and the currency is supported, but the account holds no balance in it.
 *
 * <p>Distinct from {@link ErrorCode#UNSUPPORTED_CURRENCY} because the two demand different
 * things of the caller. An unsupported currency is a permanent, service-wide fact — the same
 * request can never succeed, so it is a client bug. This one is contextual: the currency is
 * perfectly valid, the account simply does not hold it, and the request could succeed later
 * once a balance exists. A spike in one signals a broken integration; a spike in the other
 * signals customers wanting a currency they have not opened.
 *
 * <p>422 rather than 400, for the same reason as {@link InsufficientFundsException}: the
 * request is well-formed and the account exists, but its state does not permit the operation.
 * That keeps the taxonomy consistent — 400 means the request itself is wrong, 422 means the
 * request is fine and the account state forbids it.
 */
public class CurrencyNotHeldException extends BusinessException {

    public CurrencyNotHeldException(Long accountId, Currency currency) {
        super(ErrorCode.CURRENCY_NOT_HELD, HttpStatus.UNPROCESSABLE_ENTITY,
                "Account %d does not hold a %s balance".formatted(accountId, currency));
    }
}
