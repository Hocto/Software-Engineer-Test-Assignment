package com.tuum.banking.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for errors that carry their own HTTP status and {@link ErrorCode}, so
 * {@code GlobalExceptionHandler} can map them uniformly without a type switch.
 */
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    protected BusinessException(ErrorCode errorCode, HttpStatus status, String message) {
        super(message);
        this.errorCode = errorCode;
        this.status = status;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
