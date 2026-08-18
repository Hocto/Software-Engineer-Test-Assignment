package com.tuum.banking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuum.banking.AbstractIntegrationTest;
import com.tuum.banking.config.RabbitMqConfig;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.CreateTransactionRequest;
import com.tuum.banking.model.dto.TransactionResponse;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TransactionApiIT extends AbstractIntegrationTest {

    private String transactionsUrl(long accountId) {
        return "/accounts/%d/transactions".formatted(accountId);
    }

    private ResponseEntity<JsonNode> post(long accountId, String amount, Currency currency,
                                          Direction direction, String description) {
        return restTemplate.postForEntity(transactionsUrl(accountId),
                new CreateTransactionRequest(amount == null ? null : new BigDecimal(amount),
                        currency, direction, description),
                JsonNode.class);
    }

    @Test
    @DisplayName("IN credits the balance and returns balanceAfter")
    void inboundCreditsBalance() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                transactionsUrl(account.accountId()),
                new CreateTransactionRequest(new BigDecimal("120.50"), Currency.EUR, Direction.IN, "salary"),
                TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TransactionResponse body = response.getBody();
        assertThat(body.transactionId()).isPositive();
        assertThat(body.accountId()).isEqualTo(account.accountId());
        assertThat(body.balanceAfter()).isEqualByComparingTo("120.50");
        assertThat(body.description()).isEqualTo("salary");

        BigDecimal stored = jdbcTemplate.queryForObject(
                "SELECT available_amount FROM balance WHERE account_id = ? AND currency = 'EUR'",
                BigDecimal.class, account.accountId());
        assertThat(stored).isEqualByComparingTo("120.50");
    }

    @Test
    @DisplayName("OUT debits the balance")
    void outboundDebitsBalance() {
        AccountResponse account = createAccount(Currency.EUR);
        post(account.accountId(), "200.00", Currency.EUR, Direction.IN, "deposit");

        ResponseEntity<TransactionResponse> response = restTemplate.postForEntity(
                transactionsUrl(account.accountId()),
                new CreateTransactionRequest(new BigDecimal("75.25"), Currency.EUR, Direction.OUT, "rent"),
                TransactionResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().balanceAfter()).isEqualByComparingTo("124.75");
    }

    @Test
    @DisplayName("a transaction only touches the balance in its own currency")
    void doesNotAffectOtherCurrencies() {
        AccountResponse account = createAccount(Currency.EUR, Currency.USD);
        post(account.accountId(), "50.00", Currency.EUR, Direction.IN, "deposit");

        ResponseEntity<AccountResponse> account_ =
                restTemplate.getForEntity("/accounts/" + account.accountId(), AccountResponse.class);

        assertThat(account_.getBody().balances())
                .filteredOn(balance -> balance.currency() == Currency.USD)
                .singleElement()
                .satisfies(usd -> assertThat(usd.availableAmount()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("OUT beyond the balance returns 422 INSUFFICIENT_FUNDS and changes nothing")
    void insufficientFundsReturns422() {
        AccountResponse account = createAccount(Currency.EUR);
        post(account.accountId(), "10.00", Currency.EUR, Direction.IN, "deposit");

        ResponseEntity<JsonNode> response = post(account.accountId(), "10.01", Currency.EUR, Direction.OUT, "rent");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INSUFFICIENT_FUNDS");

        BigDecimal stored = jdbcTemplate.queryForObject(
                "SELECT available_amount FROM balance WHERE account_id = ? AND currency = 'EUR'",
                BigDecimal.class, account.accountId());
        assertThat(stored).isEqualByComparingTo("10.00");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM transaction", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown account returns 404")
    void unknownAccountReturns404() {
        ResponseEntity<JsonNode> response = post(999999L, "10.00", Currency.EUR, Direction.IN, "deposit");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    @DisplayName("a supported currency the account does not hold returns 422 CURRENCY_NOT_HELD")
    void unheldCurrencyReturns422() {
        AccountResponse account = createAccount(Currency.EUR);

        // SEK is a supported currency; this account simply has no SEK balance. That is an
        // account-state problem, not a malformed request — hence 422, matching how
        // INSUFFICIENT_FUNDS is treated, rather than the 400 an unsupported currency gets.
        ResponseEntity<JsonNode> response = post(account.accountId(), "10.00", Currency.SEK, Direction.IN, "deposit");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("code").asText()).isEqualTo("CURRENCY_NOT_HELD");
        assertThat(response.getBody().get("message").asText()).contains("SEK");
    }

    @Test
    @DisplayName("unsupported and unheld currencies are told apart by both status and code")
    void currencyFailuresAreDistinguishable() {
        AccountResponse account = createAccount(Currency.EUR);

        // The whole point of splitting these: a client must be able to tell "fix your request,
        // this can never work" from "valid request, open a SEK balance and retry".
        ResponseEntity<JsonNode> unsupported = restTemplate.exchange(
                RequestEntity.post(URI.create(transactionsUrl(account.accountId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"amount": 10.00, "currency": "JPY", "direction": "IN", "description": "d"}"""),
                JsonNode.class);

        ResponseEntity<JsonNode> unheld = post(account.accountId(), "10.00", Currency.SEK, Direction.IN, "d");

        assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unheld.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(unsupported.getBody().get("code").asText())
                .isNotEqualTo(unheld.getBody().get("code").asText());
    }

    @Test
    @DisplayName("an unsupported currency returns 400 UNSUPPORTED_CURRENCY")
    void invalidCurrencyReturns400() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                RequestEntity.post(URI.create(transactionsUrl(account.accountId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"amount": 10.00, "currency": "JPY", "direction": "IN", "description": "d"}"""),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("UNSUPPORTED_CURRENCY");
    }

    @Test
    @DisplayName("an unsupported direction returns 400 INVALID_DIRECTION")
    void invalidDirectionReturns400() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<JsonNode> response = restTemplate.exchange(
                RequestEntity.post(URI.create(transactionsUrl(account.accountId())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"amount": 10.00, "currency": "EUR", "direction": "SIDEWAYS", "description": "d"}"""),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("INVALID_DIRECTION");
        assertThat(response.getBody().get("message").asText()).contains("IN, OUT");
    }

    @Test
    @DisplayName("a negative amount returns 400")
    void negativeAmountReturns400() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<JsonNode> response = post(account.accountId(), "-5.00", Currency.EUR, Direction.IN, "deposit");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().get("fieldErrors").get(0).get("field").asText()).isEqualTo("amount");
    }

    @Test
    @DisplayName("a zero amount returns 400")
    void zeroAmountReturns400() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<JsonNode> response = post(account.accountId(), "0.00", Currency.EUR, Direction.IN, "deposit");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("a blank description returns 400")
    void blankDescriptionReturns400() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<JsonNode> response = post(account.accountId(), "10.00", Currency.EUR, Direction.IN, "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("fieldErrors").get(0).get("field").asText()).isEqualTo("description");
    }

    @Test
    @DisplayName("a missing description returns 400")
    void missingDescriptionReturns400() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<JsonNode> response = post(account.accountId(), "10.00", Currency.EUR, Direction.IN, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    @DisplayName("more than two decimal places is rejected rather than silently rounded")
    void excessPrecisionReturns400() {
        AccountResponse account = createAccount(Currency.EUR);

        // 10.555 is the boundary that matters: rounding it would move half a cent into a
        // real balance. Pinning it here stops a future scale change doing that quietly.
        ResponseEntity<JsonNode> response = post(account.accountId(), "10.555", Currency.EUR, Direction.IN, "d");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().get("fieldErrors").get(0).get("field").asText()).isEqualTo("amount");
    }

    @Test
    @DisplayName("POST and GET serialize the same transaction at the same scale")
    void moneySerializesAtScaleTwoEverywhere() {
        AccountResponse account = createAccount(Currency.EUR);

        // Asserted on the raw response string, not a parsed BigDecimal: deserializing
        // normalizes away exactly the discrepancy under test, which is how a POST returning
        // 250.75 while GET returned 250.7500 went unnoticed in the first place.
        String createBody = restTemplate.postForEntity(transactionsUrl(account.accountId()),
                new CreateTransactionRequest(new BigDecimal("250.75"), Currency.EUR, Direction.IN, "salary"),
                String.class).getBody();

        String listBody = restTemplate.getForEntity(transactionsUrl(account.accountId()), String.class).getBody();

        assertThat(createBody).contains("\"amount\":250.75").contains("\"balanceAfter\":250.75");
        assertThat(listBody).contains("\"amount\":250.75").contains("\"balanceAfter\":250.75");
        assertThat(createBody).doesNotContain("250.7500");
        assertThat(listBody).doesNotContain("250.7500");
    }

    @Test
    @DisplayName("a scale-2 amount is padded rather than echoed at the caller's scale")
    void wholeAmountIsPaddedToScaleTwo() {
        AccountResponse account = createAccount(Currency.EUR);

        String createBody = restTemplate.postForEntity(transactionsUrl(account.accountId()),
                new CreateTransactionRequest(new BigDecimal("40"), Currency.EUR, Direction.IN, "round"),
                String.class).getBody();

        assertThat(createBody).contains("\"amount\":40.00").contains("\"balanceAfter\":40.00");
    }

    @Test
    @DisplayName("GET returns transactions in insertion order")
    void listsTransactions() {
        AccountResponse account = createAccount(Currency.EUR);
        post(account.accountId(), "100.00", Currency.EUR, Direction.IN, "first");
        post(account.accountId(), "30.00", Currency.EUR, Direction.OUT, "second");

        ResponseEntity<List<TransactionResponse>> response = restTemplate.exchange(
                transactionsUrl(account.accountId()), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);
        assertThat(response.getBody().get(0).description()).isEqualTo("first");
        assertThat(response.getBody().get(1).description()).isEqualTo("second");
        assertThat(response.getBody().get(1).balanceAfter()).isEqualByComparingTo("70.00");
    }

    @Test
    @DisplayName("GET returns an empty list for an account with no transactions")
    void listsEmptyForNewAccount() {
        AccountResponse account = createAccount(Currency.EUR);

        ResponseEntity<List<TransactionResponse>> response = restTemplate.exchange(
                transactionsUrl(account.accountId()), HttpMethod.GET, null,
                new ParameterizedTypeReference<>() {
                });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    @DisplayName("GET returns 404 for an unknown account")
    void listUnknownAccountReturns404() {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(transactionsUrl(999999L), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("code").asText()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    @DisplayName("publishes TRANSACTION_CREATED and BALANCE_UPDATED on success")
    void publishesTransactionAndBalanceEvents() {
        AccountResponse account = createAccount(Currency.EUR);
        drainQueue(RabbitMqConfig.ACCOUNT_QUEUE);

        post(account.accountId(), "60.00", Currency.EUR, Direction.IN, "deposit");

        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            List<JsonNode> transactionEvents = drainQueue(RabbitMqConfig.TRANSACTION_QUEUE);
            assertThat(transactionEvents).hasSize(1);
            JsonNode envelope = transactionEvents.getFirst();
            assertThat(envelope.get("eventType").asText()).isEqualTo("TRANSACTION_CREATED");
            assertThat(envelope.get("payload").get("balanceAfter").decimalValue()).isEqualByComparingTo("60.00");
            assertThat(envelope.get("payload").get("direction").asText()).isEqualTo("IN");
        });

        await().atMost(ofSeconds(10)).untilAsserted(() -> {
            List<JsonNode> balanceEvents = drainQueue(RabbitMqConfig.BALANCE_QUEUE);
            assertThat(balanceEvents).hasSize(1);
            JsonNode payload = balanceEvents.getFirst().get("payload");
            assertThat(payload.get("previousAmount").decimalValue()).isEqualByComparingTo("0");
            assertThat(payload.get("availableAmount").decimalValue()).isEqualByComparingTo("60.00");
        });
    }

    @Test
    @DisplayName("publishes nothing when the transaction is rejected")
    void publishesNothingOnRejection() {
        AccountResponse account = createAccount(Currency.EUR);
        drainQueue(RabbitMqConfig.ACCOUNT_QUEUE);

        ResponseEntity<JsonNode> rejected =
                post(account.accountId(), "10.00", Currency.EUR, Direction.OUT, "overdraft");
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        // pollDelay gives the broker time to route anything that was (wrongly) published,
        // then the non-blocking drain asserts nothing arrived.
        await().pollDelay(ofSeconds(2)).atMost(ofSeconds(10)).untilAsserted(() -> {
            assertThat(drainQueueNow(RabbitMqConfig.TRANSACTION_QUEUE)).isEmpty();
            assertThat(drainQueueNow(RabbitMqConfig.BALANCE_QUEUE)).isEmpty();
        });
    }
}
