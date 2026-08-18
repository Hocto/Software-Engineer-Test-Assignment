package com.tuum.banking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuum.banking.AbstractIntegrationTest;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.CreateTransactionRequest;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@code SELECT ... FOR UPDATE} row lock actually serializes competing
 * writers.
 *
 * <p>Without it these tests fail as lost updates: concurrent requests read the same
 * starting amount, each computes its own {@code balanceAfter} from that stale value,
 * and the last write wins — money appears or disappears.
 *
 * <p>All threads are released from a single latch so they contend on the same row at
 * genuinely the same moment rather than trickling in.
 */
class ConcurrentTransactionIT extends AbstractIntegrationTest {

    private static final int THREADS = 40;

    private BigDecimal storedBalance(long accountId) {
        return jdbcTemplate.queryForObject(
                "SELECT available_amount FROM balance WHERE account_id = ? AND currency = 'EUR'",
                BigDecimal.class, accountId);
    }

    private <T> List<T> runConcurrently(Callable<T> task) {
        CountDownLatch startGate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<T>> futures = IntStream.range(0, THREADS)
                    .mapToObj(i -> executor.submit(() -> {
                        startGate.await();
                        return task.call();
                    }))
                    .toList();

            startGate.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new IllegalStateException("Concurrent request failed", e);
                }
            }).toList();
        }
    }

    @Test
    @DisplayName("concurrent deposits all apply — no lost updates")
    void concurrentDepositsAllApply() {
        AccountResponse account = createAccount(Currency.EUR);
        String url = "/accounts/%d/transactions".formatted(account.accountId());
        CreateTransactionRequest deposit =
                new CreateTransactionRequest(new BigDecimal("10.00"), Currency.EUR, Direction.IN, "concurrent deposit");

        List<HttpStatus> statuses = runConcurrently(() ->
                (HttpStatus) restTemplate.postForEntity(url, deposit, JsonNode.class).getStatusCode());

        assertThat(statuses).allMatch(status -> status == HttpStatus.CREATED);
        assertThat(storedBalance(account.accountId())).isEqualByComparingTo("400.00");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transaction WHERE account_id = ?", Integer.class, account.accountId());
        assertThat(rows).isEqualTo(THREADS);
    }

    @Test
    @DisplayName("concurrent withdrawals cannot overdraw the account")
    void concurrentWithdrawalsCannotOverdraw() {
        AccountResponse account = createAccount(Currency.EUR);
        String url = "/accounts/%d/transactions".formatted(account.accountId());

        // Fund exactly 15 of the 40 attempted withdrawals.
        restTemplate.postForEntity(url,
                new CreateTransactionRequest(new BigDecimal("150.00"), Currency.EUR, Direction.IN, "funding"),
                JsonNode.class);

        CreateTransactionRequest withdrawal =
                new CreateTransactionRequest(new BigDecimal("10.00"), Currency.EUR, Direction.OUT, "concurrent withdrawal");

        List<HttpStatus> statuses = runConcurrently(() ->
                (HttpStatus) restTemplate.postForEntity(url, withdrawal, JsonNode.class).getStatusCode());

        long succeeded = statuses.stream().filter(status -> status == HttpStatus.CREATED).count();
        long rejected = statuses.stream().filter(status -> status == HttpStatus.UNPROCESSABLE_ENTITY).count();

        assertThat(succeeded).isEqualTo(15);
        assertThat(rejected).isEqualTo(THREADS - 15);
        assertThat(storedBalance(account.accountId())).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("every committed transaction records a balanceAfter consistent with the ledger")
    void balanceAfterFormsAConsistentLedger() {
        AccountResponse account = createAccount(Currency.EUR);
        String url = "/accounts/%d/transactions".formatted(account.accountId());
        CreateTransactionRequest deposit =
                new CreateTransactionRequest(new BigDecimal("5.00"), Currency.EUR, Direction.IN, "ledger check");

        runConcurrently(() -> restTemplate.postForEntity(url, deposit, JsonNode.class).getStatusCode());

        // Serialized writers must produce a strictly increasing, gap-free sequence of
        // balanceAfter values: 5, 10, 15, ... Any interleaving would break the run.
        List<BigDecimal> ledger = jdbcTemplate.queryForList(
                "SELECT balance_after FROM transaction WHERE account_id = ? ORDER BY id",
                BigDecimal.class, account.accountId());

        assertThat(ledger).hasSize(THREADS);
        for (int i = 0; i < ledger.size(); i++) {
            assertThat(ledger.get(i)).isEqualByComparingTo(new BigDecimal("5.00").multiply(new BigDecimal(i + 1)));
        }
    }

    @Test
    @DisplayName("concurrent transactions on different currencies do not block each other")
    void differentCurrenciesAreIndependent() {
        AccountResponse account = createAccount(Currency.EUR, Currency.USD);
        String url = "/accounts/%d/transactions".formatted(account.accountId());

        ResponseEntity<JsonNode> eur = restTemplate.postForEntity(url,
                new CreateTransactionRequest(new BigDecimal("20.00"), Currency.EUR, Direction.IN, "eur"), JsonNode.class);
        ResponseEntity<JsonNode> usd = restTemplate.postForEntity(url,
                new CreateTransactionRequest(new BigDecimal("30.00"), Currency.USD, Direction.IN, "usd"), JsonNode.class);

        assertThat(eur.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(usd.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(storedBalance(account.accountId())).isEqualByComparingTo("20.00");
    }
}
