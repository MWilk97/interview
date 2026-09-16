# Ledger

In-memory ledger with an HTTP API: open accounts, read balances and post atomic, idempotent transfers
that stay correct under heavy concurrent load.

## Run

Requires JDK 21 on `PATH` (or `JAVA_HOME`). Everything else comes through the Maven wrapper.

```bash
./mvnw -pl ledger-app -am spring-boot:run
```

On Windows use `mvnw.cmd` instead of `./mvnw`. The service listens on `http://localhost:8080`.

Build and run the whole test suite:

```bash
./mvnw verify
```

## API

Amounts are integers in minor units (e.g. cents) of one implicit currency. Errors use
`application/problem+json` (RFC 9457).

| Method | Path             | Body / headers                                          | Success | Errors                                                   |
|--------|------------------|---------------------------------------------------------|---------|----------------------------------------------------------|
| POST   | `/accounts`      | `{"initialBalance": 1000}`                              | 201     | 400 negative or missing balance                          |
| GET    | `/accounts/{id}` |                                                         | 200     | 400 malformed id, 404 unknown account                    |
| POST   | `/transfers`     | header `Idempotency-Key`, `{"fromAccountId", "toAccountId", "amount"}` | 201 | 400 missing key / invalid body, 404 unknown account, 409 key reused with a different body, 422 insufficient funds |

```bash
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"initialBalance": 1000}'
# {"id":"<A>","balance":1000}

curl -s -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: order-42' \
  -d '{"fromAccountId":"<A>","toAccountId":"<B>","amount":250}'
# {"transferId":"..."}

curl -s localhost:8080/accounts/<A>
# {"id":"<A>","balance":750}
```

## Guarantees

- **Atomic transfers.** A transfer debits one account and credits the other, or changes nothing.
  Both new balances are computed before either is written, so even an arithmetic overflow on the
  credit side leaves both accounts untouched.
- **No negative balances.** The debit is checked and applied while the source account's lock is held.
  `Money` itself cannot represent a negative value.
- **No lost updates, no double-spends.** Every account has its own lock and a transfer holds both
  locks for the whole read-check-write sequence, so any interleaving of threads is equivalent to some
  serial order.
- **No deadlocks.** Locks are always taken in ascending account-id order.
- **Parallelism for unrelated accounts.** There is no global lock; transfers on disjoint account pairs
  never contend.
- **At-most-once per idempotency key.** The first request to claim a key executes the transfer.
  Any later request with the same key, including one that arrives while the first is still in
  flight, waits for and receives the original outcome. Rejections (e.g. insufficient funds) are
  replayed as well. Reusing a key with a different body is a `409 Conflict`.

## Design

```
ledger-core   plain Java, zero runtime dependencies
  Ledger                     port used by the HTTP layer
  Money, AccountId, ...      value objects (records) that enforce structural invariants
  TransferOutcome            sealed result: Completed | InsufficientFunds | UnknownAccount
  inmemory.InMemoryLedger    per-account ReentrantLock, ordered locking
  inmemory.IdempotencyGuard  ConcurrentHashMap<key, CompletableFuture<outcome>>
ledger-app    Spring Boot (web MVC), thin controllers + problem-detail error mapping
```

The HTTP layer depends only on the `Ledger` interface. Replacing the in-memory engine with a real
store means providing another `Ledger` implementation (a database would use a transaction for the
two-account update and a unique constraint on the idempotency key) and changing one bean.

Business rejections are values, not exceptions, because they are legitimate outcomes that must be
memoised and replayed for a retried key. Structurally invalid requests (same account, zero amount,
non-UUID id) are rejected by the value objects before they reach the ledger and are never recorded
against a key, since a retry with the same body can only fail the same way.

## Tests

`ledger-core` is tested without HTTP. Beyond the single-threaded behaviour, the concurrency suite
(`InMemoryLedgerConcurrencyTest`, `IdempotencyGuardTest`) demonstrates:

- 16 threads, 80 000 random transfers over 8 shared accounts: total money is preserved, no balance
  goes negative and every balance equals its start plus the deltas of transfers reported as completed.
- 64 threads racing to spend the same 100 units: exactly one succeeds.
- Opposite-direction transfers on the same pair, 80 000 times, finish without deadlock.
- A transfer on unrelated accounts completes while another transfer is deliberately held inside its
  critical section.
- 20 retries with the same key arriving while the original is held mid-flight: the transfer is applied
  once and all 21 callers receive the identical outcome.

`ledger-app` runs the API end to end on a random port and covers status codes, replayed responses
and conflict detection.

## Trade-offs

- **`long` minor units instead of `BigDecimal`.** Simpler, faster and exact for a single currency;
  overflow fails loudly rather than wrapping. Multi-currency would need a currency on `Money`.
- **Per-account locks with ordered acquisition instead of lock-free CAS.** Two-account CAS needs a
  retry loop or a multi-word CAS and is much harder to reason about. Ordered locking is provably
  deadlock-free and keeps unrelated transfers parallel, which was the goal. `ReentrantLock` rather than
  `synchronized` so virtual threads do not pin carrier threads.
- **Balance reads are lock-free.** The balance is an immutable `Money` behind a volatile reference, so a
  reader sees a complete value but may observe a moment just before or after a concurrent transfer.
- **Idempotency memory grows without bound.** Keys are never expired. A production system would add a
  TTL or persist keys next to the transfer record.
- **Infrastructure failures release the key.** If applying a transfer throws (only overflow can do that
  here), the key is freed so the client can retry; waiters on that key receive the same failure.
- **No transfer history or account listing.** Only what the requirements ask for.
- **A small test seam in the core.** `InMemoryLedger` has a package-private constructor accepting a probe
  that runs while both locks are held. It is the cheapest way to make the parallelism and in-flight
  retry tests deterministic instead of timing-based.
