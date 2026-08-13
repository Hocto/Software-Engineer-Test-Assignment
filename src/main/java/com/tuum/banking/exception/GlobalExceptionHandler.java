package com.tuum.banking.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.tuum.banking.model.dto.ErrorResponse;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Translates every exception escaping a controller into the single {@link ErrorResponse}
 * shape, so clients never see a Spring default body or a stack trace.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Domain errors that already know their status and code.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException ex, HttpServletRequest request) {
        // Expected outcomes, not faults: log at INFO without a stack trace.
        log.info("Business rule rejected {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(ErrorResponse.of(ex.getStatus().value(), ex.getErrorCode(), ex.getMessage(),
                        request.getRequestURI()));
    }

    /**
     * Bean-validation failures on {@code @Valid} request bodies.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(ErrorResponse.FieldError::field))
                .toList();

        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), ErrorCode.VALIDATION_ERROR,
                        "Request validation failed", request.getRequestURI(), fieldErrors));
    }

    /**
     * Unparseable body. An unknown enum value lands here rather than in bean validation,
     * because Jackson fails before the object is ever constructed — so this is where
     * "invalid currency" and "invalid direction" are actually detected.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                          HttpServletRequest request) {
        if (ex.getCause() instanceof InvalidFormatException ife) {
            Class<?> target = ife.getTargetType();

            if (Currency.class.equals(target)) {
                return badRequest(ErrorCode.INVALID_CURRENCY, enumMessage("currency", ife.getValue(), Currency.values()),
                        request);
            }
            if (Direction.class.equals(target)) {
                return badRequest(ErrorCode.INVALID_DIRECTION, enumMessage("direction", ife.getValue(), Direction.values()),
                        request);
            }
            return badRequest(ErrorCode.MALFORMED_REQUEST,
                    "Invalid value '%s' for field of type %s".formatted(ife.getValue(), target.getSimpleName()),
                    request);
        }

        // Do not echo ex.getMessage(): it can contain the raw payload and internal type names.
        return badRequest(ErrorCode.MALFORMED_REQUEST, "Malformed JSON request body", request);
    }

    /**
     * A path variable that cannot be bound — e.g. {@code /accounts/abc}.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return badRequest(ErrorCode.VALIDATION_ERROR,
                "Parameter '%s' has an invalid value".formatted(ex.getName()), request);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoHandler(NoHandlerFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), ErrorCode.ACCOUNT_NOT_FOUND,
                        "No handler for %s %s".formatted(ex.getHttpMethod(), ex.getRequestURL()),
                        request.getRequestURI()));
    }

    /**
     * Anything unanticipated. The real cause is logged; the client gets a generic message
     * so internal details are never leaked over the wire.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(), ErrorCode.INTERNAL_ERROR,
                        "An unexpected error occurred", request.getRequestURI()));
    }

    private ResponseEntity<ErrorResponse> badRequest(ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), code, message, request.getRequestURI()));
    }

    private static String enumMessage(String field, Object rejected, Enum<?>[] allowed) {
        return "Invalid %s '%s'. Allowed values: %s".formatted(field, rejected,
                Arrays.stream(allowed).map(Enum::name).collect(Collectors.joining(", ")));
    }
}
