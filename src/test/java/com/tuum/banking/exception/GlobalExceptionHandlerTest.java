package com.tuum.banking.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.tuum.banking.model.dto.ErrorResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Direct tests for the rendering paths that only Spring's own MVC exceptions reach.
 *
 * <p>These live here rather than in an integration test because several of them cannot be
 * provoked over HTTP against this API — there is no request that makes Spring raise a 503,
 * and a {@code WebRequest} is always a {@code ServletWebRequest} in a running servlet stack.
 * They are still worth pinning: the 5xx path is what keeps internal exception text from
 * reaching a client, and that guarantee should not depend on no one ever hitting it.
 *
 * <p>Same package as the handler so the {@code protected} template methods are reachable.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static WebRequest servletRequest(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return new ServletWebRequest(request);
    }

    private static ErrorResponse bodyOf(ResponseEntity<Object> response) {
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        return (ErrorResponse) response.getBody();
    }

    @Test
    @DisplayName("a 5xx never echoes the exception message to the client")
    void serverErrorMessageIsReplaced() {
        Exception ex = new IllegalStateException("connection string user=admin password=hunter2");

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, servletRequest("/accounts"));

        ErrorResponse body = bodyOf(response);
        assertThat(body.status()).isEqualTo(500);
        assertThat(body.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(body.message()).isEqualTo("An unexpected error occurred");
        // The whole point: nothing from the original message survives into the response.
        assertThat(body.message()).doesNotContain("password", "admin", "connection");
    }

    @Test
    @DisplayName("a 4xx does echo Spring's message, which names the offending method or type")
    void clientErrorMessageIsKept() {
        Exception ex = new IllegalStateException("Request method 'DELETE' is not supported");

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                ex, null, new HttpHeaders(), HttpStatus.METHOD_NOT_ALLOWED, servletRequest("/accounts"));

        assertThat(bodyOf(response).message()).isEqualTo("Request method 'DELETE' is not supported");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "405, METHOD_NOT_ALLOWED",
            "415, UNSUPPORTED_MEDIA_TYPE",
            "404, NOT_FOUND",
            // Any other 4xx falls through to the generic client-error code, 406 included —
            // its body is never written, so the code it maps to is immaterial.
            "400, MALFORMED_REQUEST",
            "406, MALFORMED_REQUEST",
            "409, MALFORMED_REQUEST",
            "500, INTERNAL_ERROR",
            "503, INTERNAL_ERROR"
    })
    @DisplayName("status maps to the documented error code")
    void statusMapsToCode(int status, ErrorCode expected) {
        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("boom"), null, new HttpHeaders(),
                HttpStatus.valueOf(status), servletRequest("/accounts"));

        assertThat(bodyOf(response).code()).isEqualTo(expected);
        assertThat(response.getStatusCode().value()).isEqualTo(status);
    }

    @Test
    @DisplayName("a body already shaped as ErrorResponse passes through untouched")
    void existingErrorResponseIsNotRebuilt() {
        ErrorResponse original = ErrorResponse.of(400, ErrorCode.VALIDATION_ERROR, "keep me", "/accounts");

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("ignored"), original, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, servletRequest("/accounts"));

        // Same instance, not a rebuilt copy: the overrides that construct richer bodies
        // (field errors, enum diagnostics) rely on this passing them through intact.
        assertThat(response.getBody()).isSameAs(original);
    }

    @Test
    @DisplayName("an unparseable value on a non-enum field reports the target type")
    void invalidFormatOnNonEnumField() {
        // customerId is a Long, so "abc" fails binding without involving Currency or Direction.
        InvalidFormatException cause = new InvalidFormatException(null, "not a long", "abc", Long.class);
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "unreadable", cause, new MockHttpInputMessage("{}".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, servletRequest("/accounts"));

        ErrorResponse body = bodyOf(response);
        assertThat(body.code()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
        assertThat(body.message()).contains("abc").contains("Long");
    }

    @Test
    @DisplayName("a malformed body with no Jackson cause does not leak the raw payload")
    void unreadableWithoutCauseIsGeneric() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "Unexpected character in {\"iban\":\"EE382200221020145685\"}", (Throwable) null,
                new MockHttpInputMessage("{".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<Object> response = handler.handleHttpMessageNotReadable(
                ex, new HttpHeaders(), HttpStatus.BAD_REQUEST, servletRequest("/accounts"));

        ErrorResponse body = bodyOf(response);
        assertThat(body.code()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
        assertThat(body.message()).isEqualTo("Malformed JSON request body");
        // Spring's message can quote the request body verbatim; it must not be echoed back.
        assertThat(body.message()).doesNotContain("iban", "EE382200221020145685");
    }

    @Test
    @DisplayName("a non-servlet WebRequest still yields a usable path")
    void nonServletRequestFallsBackToDescription() {
        // Defensive branch: WebRequest is always a ServletWebRequest in a running servlet
        // stack, so this cannot be provoked over HTTP — but the handler must not blow up
        // building an error response, which would turn a 4xx into a 500.
        WebRequest request = mock(WebRequest.class);
        when(request.getDescription(false)).thenReturn("uri=/accounts");

        ResponseEntity<Object> response = handler.handleExceptionInternal(
                new IllegalStateException("boom"), null, new HttpHeaders(),
                HttpStatus.BAD_REQUEST, request);

        assertThat(bodyOf(response).path()).isEqualTo("uri=/accounts");
    }
}
