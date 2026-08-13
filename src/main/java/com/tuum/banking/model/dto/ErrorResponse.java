package com.tuum.banking.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tuum.banking.exception.ErrorCode;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The single error shape returned by every 4xx and 5xx response.
 *
 * <p>{@code fieldErrors} is omitted entirely unless field-level validation failed.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        ErrorCode code,
        String message,
        String path,
        List<FieldError> fieldErrors
) {

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(int status, ErrorCode code, String message, String path) {
        return new ErrorResponse(OffsetDateTime.now(), status, code, message, path, null);
    }

    public static ErrorResponse of(int status, ErrorCode code, String message, String path,
                                   List<FieldError> fieldErrors) {
        return new ErrorResponse(OffsetDateTime.now(), status, code, message, path,
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
