# Ledger

[![CI](https://github.com/MWilk97/interview/actions/workflows/ci.yml/badge.svg)](https://github.com/MWilk97/interview/actions/workflows/ci.yml)

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

### Configuration

| Property | Default | Meaning |
|-------------------------------|-----------|---------------------------------------------------|
| `ledger.idempotency.retention` | `24h` | how long a used key is remembered |
| `ledger.idempotency.max-keys` | `1000000` | hard cap on how many keys are kept at once |

The defaults match what the core applies when used without Spring, so setting neither behaves exactly
like a plain `new InMemoryLedger()`.

## API

Amounts are integers in minor units (e.g. cents) of one implicit currency; a non-integer amount is
rejected rather than rounded, because `10.99` means the client disagrees with the API about the unit.
Errors use `application/problem+json` (RFC 9457); every `400` carries the title `Invalid request`,
and a body that fails validation also carries an `errors` object keyed by field name.

| Method | Path             | Body / headers                                          | Success | Errors                                                   |
|--------|------------------|---------------------------------------------------------|---------|----------------------------------------------------------|
| POST   | `/accounts`      | `{"initialBalance": 1000}`                              | 201     | 400 negative, non-integer or missing balance             |
| GET    | `/accounts/{id}` |                                                         | 200     | 400 malformed id, 404 unknown account                    |
| POST   | `/transfers`     | headers `X-Client-Id`, `Idempotency-Key`, `{"fromAccountId", "toAccountId", "amount"}` | 201 | 400 missing header, over-long key or invalid body, 404 unknown account, 409 key reused with a different body, 422 insufficient funds or balance limit exceeded, 503 key store full |

```bash
curl -s -X POST localhost:8080/accounts -H 'Content-Type: application/json' -d '{"initialBalance": 1000}'
# {"id":"<A>","balance":1000}

curl -s -X POST localhost:8080/transfers \
  -H 'Content-Type: application/json' \
  -H 'X-Client-Id: acme' -H 'Idempotency-Key: order-42' \
  -d '{"fromAccountId":"<A>","toAccountId":"<B>","amount":250}'
# {"transferId":"..."}

curl -s localhost:8080/accounts/<A>
# {"id":"<A>","balance":750}
```

## Guarantees

- **Atomic transfers.** A transfer debits one account and credits the other, or changes nothing.
  Both sides are checked before either is written, so a transfer that cannot complete in full
  (insufficient funds, or a credit that would exceed the maximum balance) leaves both accounts
  untouched.
- **No negative balances.** The debit is checked and applied while the source account's lock is held.
  `Money` itself cannot represent a negative value.
- **No lost updates, no double-spends.** Every account has its own lock and a transfer holds both
  locks for the whole read-check-write sequence, so any interleaving of threads is equivalent to some
  serial order.
- **No deadlocks.** Locks are always taken in ascending account-id order.
- **Parallelism for unrelated accounts.** There is no global lock over balances: transfers on disjoint
  account pairs never contend for an account lock. They do still share one idempotency store, and its
  claim counter is a single contended cache line on the path of every transfer, so measured scaling is
  well short of linear. Ordered per-account locking is what keeps unrelated accounts independent; it is
  not what sets peak throughput.
- **At-most-once per idempotency key.** Keys are scoped per client, so two clients picking `order-42`
  never collide. The first request to claim a key executes the transfer.
  Any later request with the same key, including one that arrives while the first is still in
  flight, waits for and receives the original outcome. Rejections (e.g. insufficient funds) are
  replayed as well. Reusing a key with a different body is a `409 Conflict`.

## Design

```
ledger-core   plain Java, zero runtime dependencies
  Ledger                     port used by the HTTP layer
  Money, AccountId, ...      value objects (records) that enforce structural invariants
  TransferOutcome            sealed result: Completed | InsufficientFunds
                             | BalanceLimitExceeded | UnknownAccount
  inmemory.InMemoryLedger    per-account ReentrantLock, ordered locking
  inmemory.IdempotencyGuard  ConcurrentHashMap<key, CompletableFuture<outcome>>,
                             bounded by a retention window and a hard cap
ledger-app    Spring Boot (web MVC), thin controllers, request-id filter,
              RFC 9457 problem-detail error mapping
```

The HTTP layer depends only on the `Ledger` interface. Replacing the in-memory engine with a real
store means providing another `Ledger` implementation (a database would use a transaction for the
two-account update and a unique constraint on the idempotency key) and changing one bean.

Business rejections are values, not exceptions, because they are legitimate outcomes that must be
memoised and replayed for a retried key. Structurally invalid requests (same account, zero amount,
non-UUID id) are rejected by the value objects before they reach the ledger and are never recorded
against a key, since a retry with the same body can only fail the same way.

### Balances, not postings

A real ledger is append-only: a transfer writes two immutable postings and a balance is the sum of an
account's postings. This one stores a mutable balance per account. That is a deliberate narrowing and
not a reading of the requirements: they ask for a double-entry ledger, and this is not one. It keeps
the concurrency question — the point of the exercise — sharp, and the code small. The cost is that
there is nothing to reconstruct, reconcile or audit from: the money is correct at every instant and
completely unexplained afterwards. `POST /accounts` compounds it by creating money with no contra
entry, so there is no funding account against which the books could be shown to balance, and the
`transferId` returned to a client is never stored, so nothing can be asked about it later. Adding
postings later means writing them under the same two locks that already guard the balances, and the
concurrency model does not change.

## Tests

`ledger-core` is tested without HTTP. Beyond the single-threaded behaviour, the concurrency suite
(`InMemoryLedgerConcurrencyTest`, `IdempotencyGuardTest`) demonstrates:

- 16 threads, 80 000 random transfers over 8 shared accounts: total money is preserved, no balance
  goes negative and every balance equals its start plus the deltas of transfers reported as completed.
- 64 threads racing to spend the same 100 units: exactly one succeeds.
- Opposite-direction transfers on the same pair, 80 000 times, finish without deadlock, and every one
  of them completes — conservation alone would also hold if nothing had moved. The timeouts run on a
  separate thread, so a real deadlock fails the test instead of hanging the build.
- A transfer on unrelated accounts completes while another transfer is deliberately held inside its
  critical section.
- 20 retries with the same key arriving while the original is held mid-flight: the transfer is applied
  once and all 21 callers receive the identical outcome. The test waits until every retry is parked
  inside the guard, so "none of them finished" is a statement about the guard and not about timing.
- A key expires once its retention window passes, but a key whose transfer is still running never
  does, however long it takes; the store refuses new keys past its cap, refuses them without rescanning
  itself each time, and accepts keys again once its entries expire.

`ledger-app` runs the API end to end on a random port and covers status codes, replayed responses,
conflict detection, rejection of non-integer amounts and over-long keys, the `503` a saturated key
store answers with (and that it still replays the keys it holds), and that a failing storage engine
still answers in problem+json without leaking internals. A separate suite drives the real HTTP
stack concurrently, so the guarantees are proved through the socket and not only against the core: 32
simultaneous requests carrying one idempotency key move the money once and all receive the same
`transferId`, and 64 simultaneous transfers against an account that can fund 24 of them yield exactly
24 `201`s, 40 `422`s and no overdraft.

## Trade-offs

- **`long` minor units instead of `BigDecimal`.** Simpler, faster and exact for a single currency.
  A credit that would exceed `Long.MAX_VALUE` is rejected as a business outcome rather than throwing,
  so it is memoised and replayed like any other rejection. Multi-currency would need a currency on
  `Money`.
- **Per-account locks with ordered acquisition instead of lock-free CAS.** Two-account CAS needs a
  retry loop or a multi-word CAS and is much harder to reason about. Ordered locking is provably
  deadlock-free and keeps unrelated transfers parallel, which was the goal. `ReentrantLock` rather than
  `synchronized` so virtual threads do not pin carrier threads.
- **Balance reads are lock-free, and there is no snapshot across accounts.** The balance is an immutable
  `Money` behind a volatile reference, so a reader always sees a complete value, but only for one
  account: reading A and then B while a transfer between them is in flight can show the debit without
  the credit. No endpoint reads two accounts today, so nothing observes it — the first endpoint that
  lists accounts or sums them would need a different read path (a version counter and a retry, or a
  read lock over the pair).
- **No authentication, authorisation or rate limiting.** Any caller can open an account and move money
  from any account it can name. The `422` on insufficient funds also echoes the source balance back,
  which is only acceptable because there is no notion of who is asking.
- **Idempotency keys have a retention window, not infinite memory.** Keys are kept for 24 hours and the
  store is capped at a million of them; past the window a repeated key is treated as a new request and
  executes again, so the window has to outlast any client's retry schedule. Unbounded retention would be
  a memory leak that any stream of fresh keys could turn into an outage. When the cap is reached the
  service answers `503` rather than growing. A persistent implementation would store keys next to the
  transfer record instead.
- **Those two numbers set a throughput ceiling.** A million keys over a 24-hour window is about 11.6
  transfers per second sustained. Above that the store saturates and `/transfers` answers `503` for
  *new* keys until entries age out; retries of keys it still holds keep working, because a replay
  claims nothing. Both numbers are configuration (see above), so the ceiling moves with the cap and the
  window rather than with a recompile — the cap is ultimately bounded by heap. A saturated store is
  swept at most once a second rather than once per arrival, so refusals stay cheap instead of
  rescanning a million entries per request.
- **Key length is part of the contract.** Both `X-Client-Id` and `Idempotency-Key` are capped at 255
  characters. A retained key is memory the caller chooses, so without a length bound the entry cap
  would bound the row count and not the heap.
- **Infrastructure failures release the key.** If applying a transfer throws, the key is freed so the
  client can retry; waiters on that key receive the same failure. No path in the in-memory engine
  throws today, but a persistent one would.
- **`X-Client-Id` stands in for an authenticated principal.** There is no authentication here, so the
  client names itself. In a real deployment the scope would come from the verified caller identity and
  the header would not exist; nothing else about the idempotency model would change.
- **Operational logging, not an audit trail.** Every request carries an `X-Request-Id` (taken from the
  caller when supplied) through the MDC, and each transfer logs its outcome. That is enough to follow a
  request through the logs; it is not an audit, which would need the transfer to be recorded durably.
- **No transfer history or account listing.** Only what the requirements ask for.
- **Two small test seams in the core.** `InMemoryLedger` has a package-private constructor accepting a
  probe that runs while both locks are held; it is the cheapest way to make the parallelism and
  in-flight retry tests deterministic instead of timing-based. `IdempotencyGuard` exposes its sweep
  count package-privately, so the cost of a saturated store is asserted rather than assumed.

## How this was built

I used an AI assistant (Cursor) to move faster on boilerplate and iteration. The concurrency model,
the review of the guarantees, and the tests that pin them are mine.
