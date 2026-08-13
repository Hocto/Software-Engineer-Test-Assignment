package com.tuum.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.tuum.banking.model.dto.AccountResponse;
import com.tuum.banking.model.dto.CreateTransactionRequest;
import com.tuum.banking.model.enums.Currency;
import com.tuum.banking.model.enums.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures sustained transaction throughput. Excluded from {@code test}; run with
 * {@code ./gradlew perfTest}.
 *
 * <p>Two scenarios, because the difference between them is the interesting result:
 * every write to one balance serializes on the same row lock, so single-account
 * throughput measures lock hold time, while spreading across accounts measures what
 * the service actually does when load is distributed.
 *
 * <p>Numbers from this harness are a floor, not a benchmark: the app, the load
 * generator and the containers all share one machine, and each request pays full HTTP
 * and JSON cost. The README records the measured figures with that caveat.
 */
@Tag("perf")
class ThroughputTest extends AbstractIntegrationTest {

    private static final int CONCURRENCY = 32;
    private static final int REQUESTS_PER_THREAD = 40;
    private static final int WARMUP_REQUESTS = 200;

    private record Result(int ok, int failed, Duration elapsed) {
        double throughput() {
            return ok / (elapsed.toNanos() / 1_000_000_000.0);
        }
    }

    private String url(long accountId) {
        return "/accounts/%d/transactions".formatted(accountId);
    }

    private CreateTransactionRequest deposit() {
        return new CreateTransactionRequest(new BigDecimal("1.00"), Currency.EUR, Direction.IN, "throughput");
    }

    private void warmUp(long accountId) {
        // Let the JIT compile the request path and Hikari fill the pool before timing.
        IntStream.range(0, WARMUP_REQUESTS)
                .forEach(i -> restTemplate.postForEntity(url(accountId), deposit(), JsonNode.class));
    }

    private Result measure(Callable<HttpStatus> request) {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Object>> futures = IntStream.range(0, CONCURRENCY)
                    .mapToObj(i -> executor.submit(() -> {
                        startGate.await();
                        for (int r = 0; r < REQUESTS_PER_THREAD; r++) {
                            if (request.call() == HttpStatus.CREATED) {
                                ok.incrementAndGet();
                            } else {
                                failed.incrementAndGet();
                            }
                        }
                        return null;
                    }))
                    .toList();

            long start = System.nanoTime();
            startGate.countDown();
            futures.forEach(future -> {
                try {
                    future.get();
                } catch (Exception e) {
                    throw new IllegalStateException("Load generator thread failed", e);
                }
            });
            return new Result(ok.get(), failed.get(), Duration.ofNanos(System.nanoTime() - start));
        }
    }

    private static void report(String scenario, Result result) {
        System.out.printf("%n=== %s ===%n", scenario);
        System.out.printf("  concurrency     : %d virtual threads%n", CONCURRENCY);
        System.out.printf("  transactions    : %d succeeded, %d failed%n", result.ok(), result.failed());
        System.out.printf("  elapsed         : %.2f s%n", result.elapsed().toMillis() / 1000.0);
        System.out.printf("  THROUGHPUT      : %.0f txn/s%n", result.throughput());
    }

    @Test
    @DisplayName("throughput with all transactions contending on one balance")
    void singleAccountThroughput() {
        AccountResponse account = createAccount(Currency.EUR);
        warmUp(account.accountId());

        Result result = measure(() ->
                (HttpStatus) restTemplate.postForEntity(url(account.accountId()), deposit(), JsonNode.class)
                        .getStatusCode());

        report("Single account (fully contended on one row lock)", result);
        assertThat(result.failed()).isZero();
        assertThat(result.throughput()).isPositive();
    }

    @Test
    @DisplayName("throughput with transactions spread across many accounts")
    void multiAccountThroughput() {
        List<Long> accountIds = IntStream.range(0, CONCURRENCY)
                .mapToObj(i -> createAccount(Currency.EUR).accountId())
                .toList();
        warmUp(accountIds.getFirst());

        AtomicInteger cursor = new AtomicInteger();
        Result result = measure(() -> {
            long accountId = accountIds.get(cursor.getAndIncrement() % accountIds.size());
            return (HttpStatus) restTemplate.postForEntity(url(accountId), deposit(), JsonNode.class).getStatusCode();
        });

        report("Spread across %d accounts (uncontended)".formatted(accountIds.size()), result);
        assertThat(result.failed()).isZero();
        assertThat(result.throughput()).isPositive();
    }
}
