package com.tuum.banking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuum.banking.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Protocol-level failures — the ones Spring MVC raises before any controller runs.
 *
 * <p>These exist because the suite had no coverage here at all, and that gap let a real
 * defect ship: a catch-all {@code @ExceptionHandler(Exception.class)} was intercepting
 * Spring's own MVC exceptions, so 405, 415 and 404 were all served as 500 while line
 * coverage sat at 92.7% and reported nothing wrong.
 */
class ErrorHandlingIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("an unsupported method returns 405, not 500")
    void unsupportedMethodReturns405() {
        ResponseEntity<JsonNode> response =
                restTemplate.exchange("/accounts", HttpMethod.DELETE, null, JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertStandardShape(response.getBody(), 405, "METHOD_NOT_ALLOWED", "/accounts");
    }

    @Test
    @DisplayName("an unsupported content type returns 415, not 500")
    void unsupportedMediaTypeReturns415() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<JsonNode> response = restTemplate.exchange("/accounts", HttpMethod.POST,
                new HttpEntity<>("not json", headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertStandardShape(response.getBody(), 415, "UNSUPPORTED_MEDIA_TYPE", "/accounts");
    }

    @Test
    @DisplayName("an unknown path returns 404 with a generic code, not ACCOUNT_NOT_FOUND")
    void unknownPathReturns404() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/nope", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Distinct from ACCOUNT_NOT_FOUND: a bad URL says nothing about any account.
        assertStandardShape(response.getBody(), 404, "NOT_FOUND", "/nope");
    }

    @Test
    @DisplayName("a whitespace-only country is rejected")
    void blankCountryReturns400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = """
                {"customerId":1,"country":"   ","currencies":["EUR"]}""";

        ResponseEntity<JsonNode> response = restTemplate.exchange("/accounts", HttpMethod.POST,
                new HttpEntity<>(payload, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertStandardShape(response.getBody(), 400, "VALIDATION_ERROR", "/accounts");
        assertThat(response.getBody().get("fieldErrors").get(0).get("field").asText()).isEqualTo("country");
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed rather than stored")
    void countryIsTrimmed() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String payload = """
                {"customerId":1,"country":"  EE  ","currencies":["EUR"]}""";

        ResponseEntity<JsonNode> response = restTemplate.exchange("/accounts", HttpMethod.POST,
                new HttpEntity<>(payload, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("country").asText()).isEqualTo("EE");
    }

    /**
     * Every error, whatever raised it, must carry the same fields — that uniformity is the
     * point of the global handler, so it is asserted rather than assumed.
     */
    private static void assertStandardShape(JsonNode body, int status, String code, String path) {
        assertThat(body).isNotNull();
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("path").asText()).isEqualTo(path);
        assertThat(body.get("timestamp").asText()).isNotBlank();
        assertThat(body.get("message").asText()).isNotBlank();
    }
}
