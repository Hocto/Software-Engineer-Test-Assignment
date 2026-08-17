package com.tuum.banking.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.tuum.banking.model.dto.ErrorResponse;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Translates every exception escaping a controller into the single {@link ErrorResponse}
 * shape, so clients never see a Spring default body or a stack trace.
 *
 * <p><strong>Why this extends {@link ResponseEntityExceptionHandler}:</strong> Spring resolves
 * {@code @ExceptionHandler} methods before {@code DefaultHandlerExceptionResolver} runs. A bare
 * {@code @ExceptionHandler(Exception.class)} in a standalone advice therefore intercepts Spring's
 * own MVC exceptions — {@code HttpRequestMethodNotSupportedException},
 * {@code HttpMediaTypeNotSupportedException}, {@code NoResourceFoundException} — and answers 500
 * where the correct statuses are 405, 415 and 404. Extending the base class brings in its
 * per-exception handlers, which are more specific matches than {@code Exception} and so win;
 * the catch-all then only sees genuinely unexpected faults.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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
     * A path variable that cannot be bound — e.g. {@code /accounts/abc}.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), ErrorCode.VALIDATION_ERROR,
                        "Parameter '%s' has an invalid value".formatted(ex.getName()), request.getRequestURI()));
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

    /**
     * Bean-validation failures on {@code @Valid} request bodies.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(ErrorResponse.FieldError::field))
                .toList();

        ErrorResponse body = ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), ErrorCode.VALIDATION_ERROR,
                "Request validation failed", pathOf(request), fieldErrors);

        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * Unparseable body. An unknown enum value lands here rather than in bean validation,
     * because Jackson fails before the object is ever constructed — so this is where
     * "invalid currency" and "invalid direction" are actually detected.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ErrorResponse body = describeUnreadable(ex, pathOf(request));
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    /**
     * The single rendering point for every response this class produces, inherited handlers
     * included. A body built by one of the overrides above is passed through untouched;
     * anything else — 405, 415, 406, 404 and friends, whose bodies the base class would
     * otherwise fill with a {@code ProblemDetail} — is synthesised into the same shape here.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        if (body instanceof ErrorResponse) {
            return new ResponseEntity<>(body, headers, statusCode);
        }

        ErrorCode code = codeFor(statusCode);
        // Spring's own 4xx messages name the offending method or media type and are safe to
        // echo. 5xx messages can carry internal detail, so they are replaced wholesale.
        String message = statusCode.is5xxServerError() ? "An unexpected error occurred" : ex.getMessage();

        if (statusCode.is5xxServerError()) {
            log.error("Unhandled exception on {}", pathOf(request), ex);
        }

        ErrorResponse errorResponse = ErrorResponse.of(statusCode.value(), code, message, pathOf(request));
        return new ResponseEntity<>(errorResponse, headers, statusCode);
    }

    private ErrorResponse describeUnreadable(HttpMessageNotReadableException ex, String path) {
        if (ex.getCause() instanceof InvalidFormatException ife) {
            Class<?> target = ife.getTargetType();

            if (Currency.class.equals(target)) {
                // "Unsupported", not "invalid": this is the service-wide case. A currency the
                // service supports but the account does not hold is CURRENCY_NOT_HELD (422).
                return badRequest(ErrorCode.UNSUPPORTED_CURRENCY,
                        enumMessage("Unsupported", "currency", ife.getValue(), Currency.values()), path);
            }
            if (Direction.class.equals(target)) {
                return badRequest(ErrorCode.INVALID_DIRECTION,
                        enumMessage("Invalid", "direction", ife.getValue(), Direction.values()), path);
            }
            return badRequest(ErrorCode.MALFORMED_REQUEST,
                    "Invalid value '%s' for field of type %s".formatted(ife.getValue(), target.getSimpleName()), path);
        }

        // Do not echo ex.getMessage(): it can contain the raw payload and internal type names.
        return badRequest(ErrorCode.MALFORMED_REQUEST, "Malformed JSON request body", path);
    }

    private static ErrorResponse badRequest(ErrorCode code, String message, String path) {
        return ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), code, message, path);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        if (status.equals(HttpStatus.METHOD_NOT_ALLOWED)) {
            return ErrorCode.METHOD_NOT_ALLOWED;
        }
        if (status.equals(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) {
            return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        // 406 is not listed: its body is never written, because by definition the client
        // accepts no format this service can produce. It falls through to the 4xx default,
        // which no one will ever read.
        if (status.equals(HttpStatus.NOT_FOUND)) {
            return ErrorCode.NOT_FOUND;
        }
        return status.is4xxClientError() ? ErrorCode.MALFORMED_REQUEST : ErrorCode.INTERNAL_ERROR;
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : request.getDescription(false);
    }

    /**
     * The adjective is a parameter so the message matches the {@link ErrorCode} it ships with —
     * a body reading "Invalid currency" beside a code of {@code UNSUPPORTED_CURRENCY} would
     * undercut the distinction the two currency codes exist to draw.
     */
    private static String enumMessage(String adjective, String field, Object rejected, Enum<?>[] allowed) {
        return "%s %s '%s'. Allowed values: %s".formatted(adjective, field, rejected,
                Arrays.stream(allowed).map(Enum::name).collect(Collectors.joining(", ")));
    }
}
