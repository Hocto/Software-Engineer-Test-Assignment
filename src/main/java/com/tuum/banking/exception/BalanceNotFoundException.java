package com.tuum.banking.exception;

import com.tuum.banking.model.enums.Currency;
import org.springframework.http.HttpStatus;

/**
 * The account exists but holds no balance in the requested currency.
 *
 * <p>400 rather than 404: the resource addressed by the URL ({@code /accounts/{id}})
 * does exist — it is the request body that names a currency this account cannot
 * transact in.
 */
public class BalanceNotFoundException extends BusinessException {

    public BalanceNotFoundException(Long accountId, Currency currency) {
        super(ErrorCode.BALANCE_NOT_FOUND, HttpStatus.BAD_REQUEST,
                "Account %d has no %s balance".formatted(accountId, currency));
    }
}
