package com.tuum.banking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuum.banking.AbstractIntegrationTest;
import com.tuum.banking.config.RabbitMqConfig;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.CreateAccountRequest;
import com.tuum.banking.model.enums.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.time.Duration.ofSeconds;

class AccountApiIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("POST /accounts creates the account with a zero balance per currency")
    void createsAccount() {
        ResponseEntity<AccountResponse> response = restTemplate.postForEntity("/accounts",
                new CreateAccountRequest(77L, "EE", List.of(Currency.EUR, Currency.USD)), AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        AccountResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accountId()).isPositive();
        assertThat(body.customerId()).isEqualTo(77L);
        assertThat(body.country()).isEqualTo("EE");
        assertThat(body.balances()).hasSize(2)
                .allSatisfy(balance -> assertThat(balance.availableAmount()).isEqualByComparingTo("0"));

        Integer persisted = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM balance WHERE account_id = ?", Integer.class, body.accountId());
        assertThat(persisted).isEqualTo(2);
    }

    @Test
    @DisplayName("POST /accounts publishes ACCOUNT_CREATED to the account queue")
    void publishesAccountCreatedEvent() {
        AccountResponse account = createAccount(Currency.EUR);

        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            List<JsonNode> messages = drainQueue(RabbitMqConfig.ACCOUNT_QUEUE);
            assertThat(messages).hasSize(1);

            JsonNode envelope = messages.getFirst();
            assertThat(envelope.get("eventType").asText()).isEqualTo("ACCOUNT_CREATED");
            assertThat(envelope.get("eventId").asText()).isNotBlank();
            assertThat(envelope.get("occurredAt").asText()).isNotBlank();

            JsonNode payload = envelope.get("payload");
            assertThat(payload.get("accountId").asLong()).isEqualTo(account.accountId());
            assertThat(payload.get("country").asText()).isEqualTo("EE");
            assertThat(payload.get("balances")).hasSize(1);
            assertThat(payload.get("balances").get(0).get("currency").asText()).isEqualTo("EUR");
        });
    }

    @Test
    @DisplayName("POST /accounts rejects an unsupported currency with 400 UNSUPPORTED_CURRENCY")
    void rejectsInvalidCurrency() {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                RequestEntity.post(URI.create("/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"customerId": 1, "country": "EE", "currencies": ["EUR", "JPY"]}"""),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code").asText()).isEqualTo("UNSUPPORTED_CURRENCY");
        assertThat(body.get("message").asText()).contains("JPY").contains("EUR, SEK, GBP, USD");
        assertThat(body.get("path").asText()).isEqualTo("/accounts");
        assertThat(body.get("status").asInt()).isEqualTo(400);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM account", Integer.class)).isZero();
    }

    @Test
    @DisplayName("POST /accounts rejects an empty currency list with field-level detail")
    void rejectsEmptyCurrencies() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/accounts",
                new CreateAccountRequest(1L, "EE", List.of()), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.get("fieldErrors")).isNotNull();
        assertThat(body.get("fieldErrors").get(0).get("field").asText()).isEqualTo("currencies");
    }

    @Test
    @DisplayName("POST /accounts rejects a missing customerId")
    void rejectsMissingCustomerId() {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/accounts",
                new CreateAccountRequest(null, "EE", List.of(Currency.EUR)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("fieldErrors").get(0).get("field").asText()).isEqualTo("customerId");
    }

    @Test
    @DisplayName("POST /accounts rejects malformed JSON")
    void rejectsMalformedJson() {
        ResponseEntity<JsonNode> response = restTemplate.exchange(
                RequestEntity.post(URI.create("/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{not json"),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    @DisplayName("GET /accounts/{id} returns the account with balances")
    void getsAccount() {
        AccountResponse created = createAccount(Currency.EUR, Currency.GBP);

        ResponseEntity<AccountResponse> response =
                restTemplate.getForEntity("/accounts/" + created.accountId(), AccountResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().accountId()).isEqualTo(created.accountId());
        assertThat(response.getBody().balances()).hasSize(2);
    }

    @Test
    @DisplayName("GET /accounts/{id} returns 404 for an unknown account")
    void getUnknownAccountReturns404() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/accounts/999999", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonNode body = response.getBody();
        assertThat(body.get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(body.get("status").asInt()).isEqualTo(404);
        assertThat(body.get("path").asText()).isEqualTo("/accounts/999999");
    }

    @Test
    @DisplayName("GET /accounts/{id} returns 400 for a non-numeric id")
    void getNonNumericAccountIdReturns400() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/accounts/not-a-number", JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }
}
