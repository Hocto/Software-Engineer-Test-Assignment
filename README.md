# Core Banking Service

A small core banking service: customer accounts with per-currency balances, a transaction
history that mutates those balances atomically, and a RabbitMQ event stream for downstream
consumers.

Built for the Tuum Software Engineer Test Assignment.

| | |
|---|---|
| **Java** | 25 (Amazon Corretto) — assignment asks for 17+ |
| **Framework** | Spring Boot 3.5.16 |
| **Persistence** | MyBatis 3.0.5 + PostgreSQL 16, schema via Flyway |
| **Messaging** | RabbitMQ 3.13 (topic exchange) |
| **Build** | Gradle 9.7 (wrapper committed) |
| **Tests** | JUnit 5, Mockito, Testcontainers — **93.9% line coverage**, 65 tests |

---

## Running it

The only prerequisite is Docker. No JDK, no Gradle, no PATH changes.

```bash
docker compose up --build
```

That starts Postgres, RabbitMQ and the application. The app waits until both dependencies
report *healthy*, then Flyway applies `V1__init.sql` before the first request is served.

| Service | Base URL | Override with |
|---|---|---|
| Application | `http://localhost:8080` | `APP_PORT` |
| RabbitMQ management | `http://localhost:15672` — `guest` / `guest` | `RABBITMQ_UI_PORT` |

Those two rows are the **only** place this README names a port. Everything else is written as
a path, so it stays correct whichever ports you run on:

| What | Path |
|---|---|
| Swagger UI | `/swagger-ui.html` |
| OpenAPI spec | `/v3/api-docs` |
| Health | `/actuator/health` |
| API | `/accounts`, `/accounts/{id}/transactions` |

To have the running stack tell you which host port it actually bound, instead of working it
out from what you passed:

```bash
docker compose port app 8080
```

**If a port is already taken** — both host ports are overridable, no file edits:

```bash
APP_PORT=9090 RABBITMQ_UI_PORT=15673 docker compose up --build
```

If the API returns unexpected 404s while every container reports healthy, another process
may already hold 8080 — some Docker runtimes bind over it silently rather than failing. The
override above is the fix; the symptom just doesn't look like a port clash.

Postgres (5432) and the AMQP port (5672) are deliberately **not** published to the host.
The app reaches them over the compose network, and binding them is the most common way a
stack like this fails on someone else's machine. (It happened during development here: an
unrelated container already held 5432.)

**If you ran an earlier build of this branch**, wipe the volume first — `V1__init.sql` was
revised (money moved to `NUMERIC(19,2)`) and Flyway will refuse to validate a database that
applied the previous version:

```bash
docker compose down -v
```

### Building and testing locally

Requires Docker running, for Testcontainers. Gradle provisions its own Corretto 25, so no
JDK install is needed.

```bash
./gradlew build
```

This runs unit tests, integration tests and the 80% Jacoco coverage gate. Reports land in
`build/reports/tests/test/index.html` and `build/reports/jacoco/test/html/index.html`.

The throughput harness is excluded from the normal test run:

```bash
./gradlew perfTest
```

> **On Docker Desktop there is nothing to configure.**
>
> On other runtimes — Colima, Rancher Desktop, Podman, rootless — Testcontainers only
> auto-detects Docker Desktop's socket and would otherwise fail with *"Could not find a valid
> Docker environment"*. The build asks `docker context inspect` where the daemon actually
> listens and passes that to the test JVM, so nothing needs exporting there either. An
> explicit `DOCKER_HOST` always takes precedence, and on Windows (`npipe://`) detection stands
> aside and lets Testcontainers do its own thing.

---

## API

### `POST /accounts` → 201

```json
{ "customerId": 501, "country": "EE", "currencies": ["EUR", "USD"] }
```

Creates the account and one zero balance per currency, atomically.

```json
{
  "accountId": 1, "customerId": 501, "country": "EE",
  "balances": [
    { "availableAmount": 0.00, "currency": "EUR" },
    { "availableAmount": 0.00, "currency": "USD" }
  ]
}
```

`country` is trimmed; a whitespace-only value is rejected with 400.

Repeated currencies are collapsed rather than rejected — the request's intent is a *set*,
and the outcome (one balance per named currency) is the same either way.

### `GET /accounts/{accountId}` → 200

Returns the account with its current balances.

### `POST /accounts/{accountId}/transactions` → 201

```json
{ "amount": 250.75, "currency": "EUR", "direction": "IN", "description": "salary" }
```

```json
{
  "transactionId": 1, "accountId": 1, "amount": 250.75, "currency": "EUR",
  "direction": "IN", "description": "salary", "balanceAfter": 250.75
}
```

`IN` credits the balance, `OUT` debits it. Amounts are normalised to two decimals on the
way in, so `40` comes back as `40.00` and the same transaction reads identically from
`POST` and `GET`. More than two decimals is rejected with 400 rather than rounded.

### `GET /accounts/{accountId}/transactions` → 200

Returns the account's transactions in insertion order.

### Errors

Every failure — including 500s — returns the same shape:

```json
{
  "timestamp": "2026-08-13T17:55:20.297Z",
  "status": 400,
  "code": "UNSUPPORTED_CURRENCY",
  "message": "Unsupported currency 'JPY'. Allowed values: EUR, SEK, GBP, USD",
  "path": "/accounts",
  "fieldErrors": [ { "field": "amount", "message": "amount must be greater than zero" } ]
}
```

`fieldErrors` is omitted unless field-level validation failed. `code` is the stable,
machine-readable identifier — branch on it rather than on status or message text.

| Condition | Status | `code` |
|---|---|---|
| Currency outside EUR/SEK/GBP/USD | 400 | `UNSUPPORTED_CURRENCY` |
| Direction outside IN/OUT | 400 | `INVALID_DIRECTION` |
| Amount ≤ 0, blank description or country, missing field, >2 decimal places | 400 | `VALIDATION_ERROR` |
| Unparseable JSON body | 400 | `MALFORMED_REQUEST` |
| Unsupported HTTP method for the path | 405 | `METHOD_NOT_ALLOWED` |
| `Content-Type` the endpoint cannot consume | 415 | `UNSUPPORTED_MEDIA_TYPE` |
| Unknown path | 404 | `NOT_FOUND` |
| Account does not exist | 404 | `ACCOUNT_NOT_FOUND` |
| Account exists but holds no balance in that currency | **422** | `CURRENCY_NOT_HELD` |
| OUT exceeds available funds | **422** | `INSUFFICIENT_FUNDS` |
| Anything unhandled | 500 | `INTERNAL_ERROR` |

A request whose `Accept` header matches nothing this service produces gets a bare **406 with
no body** — not an `ErrorResponse`. That is unavoidable rather than an oversight: 406 means
the client accepts no format we can write, so the error body cannot be written either. There
is deliberately no error code for it, since none could ever be delivered.

The statuses follow one rule, which is worth stating because it decides the ambiguous cases:

> **400** — the request itself is wrong · **404** — the addressed resource does not exist ·
> **422** — the request is fine, the account's state forbids it

**The assignment's single "Invalid currency" error maps to two codes here**, because the two
situations behind it demand different things of the caller:

| | `UNSUPPORTED_CURRENCY` (400) | `CURRENCY_NOT_HELD` (422) |
|---|---|---|
| Example | `JPY` — the service supports no such currency | `SEK` on an EUR-only account |
| Nature | Absolute, service-wide | Contextual, per-account |
| Retrying the same request | Can never succeed | May succeed once a balance exists |
| What it signals | A broken client integration | A customer wanting a currency they have not opened |

Neither code is named `INVALID_CURRENCY`, deliberately. Attaching the specification's exact
term to just one of these two would misdirect anyone tracing requirements to code — the
reader would find the familiar name and conclude it covered the case they had in mind.
`INVALID_DIRECTION` keeps its literal wording because direction has only one failure mode,
so no such ambiguity arises.

`GlobalExceptionHandler` extends Spring's `ResponseEntityExceptionHandler` rather than
standing alone. That detail is load-bearing: `@ExceptionHandler` methods are resolved before
`DefaultHandlerExceptionResolver` runs, so a lone `@ExceptionHandler(Exception.class)` will
intercept Spring's own MVC exceptions and answer 500 where 405, 415 or 404 is correct.
Extending the base class brings in its per-exception handlers, which are more specific
matches and therefore win; `handleExceptionInternal` is overridden as the single point that
renders every one of them into the shape above. `ErrorHandlingIT` pins this.

**Insufficient funds is 422, not 400.** The request is syntactically valid and the account
exists — a business rule blocks it. That separation lets a client distinguish "fix your
request" from "the request was fine, the account state does not permit it", which are
different things to a caller and often different code paths.

`CURRENCY_NOT_HELD` is 422 for exactly the same reason, and it started out as a 400. The
earlier reasoning — *the URL's resource exists, so the fault is in the body* — was self
consistent but produced an incoherent result: two failures that are both "valid request,
account state forbids it" answered with different statuses. The rule above resolves it.

---

## Events

Every insert and update is published to the durable topic exchange **`banking.events`**.

| Routing key | Queue | Published when |
|---|---|---|
| `account.created` | `banking.account.queue` (`account.#`) | An account and its balances are created |
| `transaction.created` | `banking.transaction.queue` (`transaction.#`) | A transaction is committed |
| `balance.updated` | `banking.balance.queue` (`balance.#`) | A balance amount changes |

A topic exchange (rather than direct or fanout) lets a new consumer subscribe to a slice of
the stream — `account.#`, or everything with `#` — without the publisher changing.

All three share one envelope, so a consumer can route on `eventType` and deduplicate on
`eventId` without knowing any payload schema:

```json
{
  "eventId": "ccac4d67-2fda-4455-8a26-470716219310",
  "eventType": "TRANSACTION_CREATED",
  "occurredAt": "2026-08-13T17:55:08.083Z",
  "payload": {
    "transactionId": 1, "accountId": 1, "amount": 250.75, "currency": "EUR",
    "direction": "IN", "description": "salary", "balanceAfter": 250.75
  }
}
```

`BALANCE_UPDATED` carries both sides of the change (`previousAmount`, `availableAmount`) so
a consumer can reconcile without querying back.

---

## Design decisions

### Concurrent balance updates use a pessimistic row lock

This is the heart of the service. `TransactionService.createTransaction` reads the balance
with `SELECT ... FROM balance WHERE account_id = ? AND currency = ? FOR UPDATE`, so
competing writers on the same *(account, currency)* queue behind it.

Without that lock, two concurrent withdrawals both read the same starting amount, both pass
the sufficient-funds check, and the second write silently discards the first — the classic
lost update, and an overdraft.

Pessimistic rather than optimistic: a balance row is the natural contention point, and
optimistic locking would mean retry loops on exactly the rows that are hottest, converting
contention into wasted work and tail latency. A `version` column is kept on the table for
auditing and as an optimistic escape hatch if a future write path wants it.

The balance update and the transaction insert share one `@Transactional` boundary, so the
ledger can never disagree with the balance. `ConcurrentTransactionIT` enforces all of this —
it fails if the `FOR UPDATE` is removed.

### Behaviour sits on the type that owns it

`Direction` carries its own arithmetic (`IN(BigDecimal::add)`, `OUT(BigDecimal::subtract)`)
rather than the service branching on it. The reason is failure mode, not neatness: a
`direction == IN ? add : subtract` conditional elsewhere can be left unextended when a
constant is added, and it still compiles — the new direction silently takes the `else` arm
and money moves the wrong way. Supplying the operation at the constant makes that a compile
error instead.

`Direction.applyTo` returns the result without judging it. Rejecting an overdraft needs
account context the enum does not have, so that decision stays in `TransactionService`.

### Events publish *after* commit, not during

Domain events are raised via `ApplicationEventPublisher` and relayed to RabbitMQ from a
`@TransactionalEventListener(phase = AFTER_COMMIT)`.

Publishing is split across two classes on either side of an interface, because they change
for different reasons:

| | Knows about | Changes when |
|---|---|---|
| `DomainEventPublisher` (interface) | events, transactions | the domain gains a new event |
| `AfterCommitEventPublisher` | Spring's event machinery | delivery timing changes |
| `RabbitEventRelay` | exchanges, routing keys, `RabbitTemplate` | the transport changes |

Services depend only on the interface, so swapping RabbitMQ for Kafka or the outbox described
below rewrites `RabbitEventRelay` and touches no business logic. This is the one abstraction
in the codebase written for a *documented* second implementation rather than a hypothetical
one — the outbox is named as required future work, not merely imagined.

Adding a second destination — an audit log, a metrics sink — is a new
`@TransactionalEventListener`, with no edit to any existing class.

Publishing inline with the write would emit events for work that later rolls back — an OUT
rejected for insufficient funds, or any failure before commit — and a consumer cannot un-see
a message.

The tradeoff is the opposite failure mode: a broker outage in the window *after* commit
loses the event, because the database work is already durable. That is the dual-write
problem, and closing it properly needs a transactional outbox (see scaling, below). For this
service the trade is right, because the database is the system of record and the event
stream is a notification. A publish failure is logged loudly rather than rethrown — failing
the HTTP response for work that already committed would be strictly worse.

### Money is `NUMERIC(19,2)` and `BigDecimal`, never floating point

Binary floating point cannot represent decimal fractions exactly. `double` is disqualified
for balances.

Scale 2 is the ISO-4217 minor unit of every supported currency — EUR, SEK, GBP and USD all
subdivide into hundredths — so it is the precision the domain actually has, not a
simplification. Requests carrying more than two decimals are rejected with 400 rather than
rounded, so a caller is never quietly charged a different amount than it asked for.

`com.tuum.banking.model.Money` holds that scale in one place and normalises on the way in.
Without normalisation the same transaction serialises two ways — `POST` echoing the caller's
scale (`250.75`) while `GET` returns the column's — which is a contract bug that only shows
up in raw bytes, not in a deserialised `BigDecimal`. `TransactionApiIT` asserts on the raw
response string for exactly that reason.

The constraint this accepts: admitting a currency with a different minor unit — JPY at 0,
KWD at 3 — would need a schema migration and a per-currency scale lookup, not just a wider
column. For a fixed four-currency service that is the right trade.

A `CHECK (available_amount >= 0)` constraint sits behind the application's funds check as a
database-level backstop.

### MyBatis with XML mappers

MyBatis is required by the assignment. Given that, XML mappers over annotations: the one
query whose exact SQL determines correctness is the `FOR UPDATE` read, and it is worth
having that visible and reviewable rather than generated. All parameters bind through `#{}`,
so statements are parameterized and not string-concatenated.

Worth being straight about the tradeoff: JPA/Hibernate would handle this domain perfectly
well — `@Lock(PESSIMISTIC_WRITE)` emits the same `SELECT ... FOR UPDATE`, and `@Version`
would give optimistic locking for less code than the hand-rolled `version` column here. The
JPA version would likely be shorter. MyBatis is the stack the assignment names; the SQL
visibility is a genuine benefit on top, not the justification.

### `balanceAfter` is stored, not derived

Each transaction row records the balance it produced, so the history is a self-contained
ledger and answering "what was the balance after this movement?" never requires replaying
the account.

### Other choices

- **Flyway at application startup**, not a compose init script — the schema version travels
  with the code, and the same migration runs identically in tests, Docker and any future
  environment.
- **Actuator exposes only `health` and `info`**; no `env`, `beans` or `heapdump` over HTTP.
- **The container runs as a non-root user**, and error responses never include stack traces
  or internal type names.

---

## Throughput

**Measured: ~1330 txn/s distributed across accounts, ~450 txn/s fully contended on a single
balance.**

### How it was measured

`ThroughputTest` (`./gradlew perfTest`) drives real HTTP `POST /accounts/{id}/transactions`
requests through the full stack — controller, validation, MyBatis, Postgres, and after-commit
event publish — against Testcontainers Postgres 16 and RabbitMQ 3.13. 32 virtual threads are
released from a single latch, 40 requests each (1280 total), after a 200-request warmup so
the JIT has compiled the request path and the Hikari pool is filled.

Two scenarios, because the gap between them is the actual finding:

| Scenario | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| Spread across 32 accounts (uncontended) | 1332 txn/s | 1330 txn/s | 1593 txn/s |
| Single account (every write on one row lock) | 436 txn/s | 460 txn/s | 420 txn/s |

Run-to-run spread is roughly ±20% on a 2-CPU VM, so read these as an order of magnitude
rather than a precise figure.

> **Run this with the compose stack stopped.** `perfTest` starts its own Testcontainers
> Postgres and RabbitMQ, so leaving `docker compose up` running puts two databases, two
> brokers and the application container on the same 2 CPUs. Measured with the stack up:
> **996 txn/s uncontended, 310 contended** — about 30% lower, and nothing to do with the
> code. `docker compose down` first.

### Hardware, and why these are a floor

Apple Silicon (arm64), with Docker provided by **Colima allocated only 2 CPUs and 2 GB RAM**.
The application JVM, the load generator and both containers all share that one small box, and
every request pays full HTTP and JSON cost. A dedicated host, a separate load generator, and
a normally-sized Docker VM would all push these numbers up. Treat them as a lower bound
measured honestly, not a benchmark.

The ~3x gap is the interesting part, and it is the row lock doing its job: single-account
throughput measures how long the lock is held (one `SELECT FOR UPDATE`, one `UPDATE`, one
`INSERT`, one commit), while the distributed figure measures what the service does when load
spreads across rows and locks stop overlapping. Real traffic looks far more like the second
case — contention concentrates only on unusually hot accounts.

---

## Scaling horizontally

**The application layer is already stateless** — no sessions, no in-memory caches, no
sticky routing. All shared state lives in Postgres and RabbitMQ, so running N instances
behind a load balancer needs no code change. That is the easy half.

The real constraints are below the app:

**Postgres connections are the first ceiling.** Each instance carries its own Hikari pool
(default 20 here). Ten instances is 200 connections against a default `max_connections` of
100, and Postgres degrades badly past a few hundred backends because each one is a process.
Fix in this order: size pools to actual concurrency rather than optimism, then put PgBouncer
in transaction-pooling mode between the app and the database. Note that transaction pooling
forbids session-level state — this service uses none, which is partly why it is a clean fit.

**The row lock scales with key distribution, not instance count.** Because contention is
per *(account, currency)*, adding instances genuinely adds throughput for distributed
traffic. It adds nothing for a single hot account: those writes serialize on one row no
matter how many instances exist. If specific accounts become hot, the answer is not more
app servers but changing the write pattern — batching, or an append-only ledger with
periodically materialised balances so writes stop contending on a single mutable row.

**The database becomes the bottleneck before the app does.** In order: read replicas for the
`GET` endpoints (both are trivially replica-safe), then partitioning `transaction` by
`account_id` or by time as history grows, then sharding by `customer_id` if a single primary
is genuinely saturated. Sharding is last because it costs cross-shard queries and is hard to
undo.

**Events need an outbox before scaling out.** The after-commit publish described above
accepts losing an event if the broker is down at exactly the wrong moment. That is tolerable
for one instance and a notification stream; it is not tolerable once consumers do real work
and instances multiply the exposure. The fix is a transactional outbox — write the event to
an `outbox` table inside the same transaction, and have a relay poll and publish it. That
makes delivery at-least-once, which is why consumers should already be deduplicating on the
`eventId` the envelope carries. On the consumer side, RabbitMQ scales by adding competing
consumers on the same queue; if per-account ordering is ever required, events must be
partitioned by account so one consumer owns a given account's stream.

**Write idempotency is a prerequisite for safe retries.** There is no idempotency key today,
so a client that retries a timed-out `POST /transactions` can double-apply a movement. Behind
a load balancer with retries, that stops being theoretical. An `Idempotency-Key` header with
a unique index would make retries safe, and would be my first addition before scaling out.

---

## Project layout

```
src/main/java/com/tuum/banking/
├── controller     AccountController, TransactionController
├── service        AccountService, TransactionService  ← business rules and locking
├── repository     MyBatis mapper interfaces (SQL in resources/mapper/*.xml)
├── model
│   ├── entity     Account, Balance, Transaction
│   ├── enums      Currency, Direction
│   └── dto        request/response records, ErrorResponse
├── messaging      DomainEventPublisher (interface) + AfterCommitEventPublisher
│                  + RabbitEventRelay + event payloads
├── config         RabbitMqConfig, OpenApiConfig
└── exception      domain exceptions + GlobalExceptionHandler

src/main/resources/
├── db/migration   V1__init.sql          ← Flyway, applied at startup
└── mapper         MyBatis XML statements
```

### Tests

| Suite | What it covers |
|---|---|
| `AccountServiceTest`, `TransactionServiceTest` | Service logic against mocked mappers |
| `AccountApiIT`, `TransactionApiIT` | All four endpoints, every error path, real queue payloads |
| `ConcurrentTransactionIT` | No lost updates, no overdraft, gap-free ledger under 40-thread contention |
| `ThroughputTest` (`perf`) | Throughput measurement, excluded from `test` |

Coverage across **65 tests**, from `build/reports/jacoco/test/jacocoTestReport.xml`:

| Counter | Covered | |
|---|---|---|
| Line | **93.9%** | 230 / 245 |
| Instruction | 90.8% | 941 / 1036 |
| Method | 95.2% | 80 / 84 |
| Branch | **78.1%** | 25 / 32 |

`jacocoTestCoverageVerification` gates on **line** coverage at 80%. Config classes, DTOs and
event records are excluded — they carry no branching logic worth counting.

Branch coverage is reported honestly rather than quietly omitted: at 78.1% it sits below the
line figure, and below the gate. The denominator is why it moves so sharply — there are only
32 branches in the whole gated set, so a single uncovered one costs about three points. That
number fell as the code improved, not as it decayed: replacing the `direction == IN ? add :
subtract` conditional with behaviour on the enum, and dropping the unreachable
`NOT_ACCEPTABLE` case, both removed branches that *were* covered, leaving a smaller
denominator behind. Gating on a metric that punishes deleting conditionals would be the wrong
incentive, which is why the gate is on lines.

---

## Known limitations

Honest scope boundaries, not oversights:

- **No authentication or authorization.** Out of scope for the assignment. In production
  every endpoint would sit behind authentication, and `customerId` would come from the
  token rather than the request body — as written, a caller can create an account for any
  customer id.
- **No idempotency keys**, so a retried `POST /transactions` can double-apply. See scaling.
- **No rate limiting.**
- **`GET /accounts/{id}/transactions` is unpaginated.** Fine for the assignment; an account
  with a long history would need cursor pagination.
- **`country` is validated as free text**, not against ISO-3166. Deliberate — rejecting a
  reviewer's plausible input seemed worse than accepting a loose value.
- **Events can be lost if the broker is down** in the window after commit. See scaling.

---

## AI tool usage

This solution was produced with **Claude Code (Claude Opus 5)** used as an active pair
programmer. In the interest of full disclosure:

**What AI did:** generated the bulk of the implementation, tests, Dockerfile and this README
from a written specification and a reviewed plan; researched current library versions against
Maven Central rather than relying on training data; and diagnosed the failures below.

**What was human-directed:** the requirements and technology choices; the decision to use
422 for insufficient funds; the choice of Java 25 / Amazon Corretto; the delivery workflow;
and review of the output at each step.

**Problems found and fixed during development** — recorded because they show what was
actually verified rather than assumed:

- Testcontainers failed with "Could not find a valid Docker environment" — Colima's socket is
  not auto-detected. First worked around with local environment variables and merely documented,
  which was the wrong answer: it pushed setup onto anyone not using Docker Desktop. The build
  now asks `docker context inspect` where the daemon listens. Hardcoding a Colima path would
  have broken Docker Desktop reviewers; asking Docker breaks nobody.
- An `await()` in the "publishes nothing on rejection" test used a blocking queue read, which
  cannot assert absence inside its own timeout. Fixed with a non-blocking drain plus
  `pollDelay`.
- The first jlink runtime used a `jdeps`-derived module list and crashed at startup with
  `ClassNotFoundException: java.beans.PropertyEditorSupport`, because Spring resolves that
  class reflectively. Replaced with a complete-but-stripped runtime.
- `docker compose up` failed on `Bind for 0.0.0.0:5432 failed: port is already allocated`
  against an unrelated container on the dev machine. This drove the decision not to publish
  infrastructure ports at all.

**Defects found by a later self-review, after the first version was already "done"** — worth
listing separately, because the suite passed at 92.7% line coverage *at that point* and
reported none of them. That figure is history, not the current one above; it is quoted to
make the point that a green bar said nothing about any of these:

- `GlobalExceptionHandler` answered **500** for `405`, `415` and `404`. A standalone
  `@ExceptionHandler(Exception.class)` was intercepting Spring's own MVC exceptions before
  `DefaultHandlerExceptionResolver` could map them. No test covered protocol-level failures,
  so nothing caught it. Fixed by extending `ResponseEntityExceptionHandler`; `ErrorHandlingIT`
  now pins all four cases.
- The same transaction serialised two ways: `POST` returned `amount: 250.75`, `GET` returned
  `250.7500`. Fixed by normalising through `Money`, and money moved to scale 2 to match the
  ISO-4217 minor unit of the supported currencies.
- The README documented payloads the service never emitted (`0.0` where it produced `0.0000`).
  Cause: the original examples were captured through `python3 -m json.tool`, which reparsed
  the decimals as floats and reprinted them normalised. Every example here is now pasted from
  raw `curl` bytes.
- `country` accepted a whitespace-only value, because `@Size(min = 1)` counts characters.
  Now `@NotBlank` plus trimming in the record's compact constructor.

**Verification is all first-hand.** Every number here was measured on this machine, not
estimated: the coverage figures come from the Jacoco XML report, the throughput figures from
two `perfTest` runs, and the API behaviour from `curl` against the running compose stack —
including a 50-way parallel burst confirming exactly 10 of 50 withdrawals succeeded against a
100.00 balance, leaving exactly `0.00`.
