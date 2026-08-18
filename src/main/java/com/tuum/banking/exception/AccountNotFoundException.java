package com.tuum.banking.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends BusinessException {

    public AccountNotFoundException(Long accountId) {
        super(ErrorCode.ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND,
                "Account %d was not found".formatted(accountId));
    }
}
