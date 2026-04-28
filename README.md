# FintechPay — Production-Grade Payment System

A Spring Boot payment system built with every pattern a fintech engineer needs on their resume.

---

## Architecture Overview

```
Client
  │
  │  POST /api/v1/payments/transfer
  │  Header: Idempotency-Key: <uuid>
  │
  ▼
PaymentController
  │
  ├─► IdempotencyService ──► Redis (L1 cache)
  │                     └──► PostgreSQL (L2 durable)
  │
  ├─► PaymentService (validation, PENDING record creation)
  │
  └─► PaymentQueueProducer
              │
              ▼
        RabbitMQ Exchange
              │
              ▼
        payment.processing (queue)
              │
              ▼
        PaymentQueueConsumer
              │
              ├─► PaymentProcessor
              │       ├─► Wallet (Optimistic Lock @Version)
              │       ├─► LedgerService (double-entry)
              │       └─► Transaction (state machine)
              │
              ├── On retryable failure ──► payment.retry (TTL queue)
              │                                   │
              │                              [TTL expires]
              │                                   │
              │                                   ▼
              │                           payment.processing
              │
              └── After max retries ──► payment.dead-letter (DLQ)
                                                  │
                                                  ▼
                                        DLQ Consumer → DEAD_LETTERED
```

---

## Advanced Features Implemented

### 1. Idempotency (Two-Layer)
- **Layer 1 (Redis)**: Sub-millisecond O(1) lookup. TTL-based auto-expiry (24h).
- **Layer 2 (PostgreSQL)**: Durable record that survives Redis flush or restart.
- **Request fingerprinting**: SHA-256 hash of request body detects key reuse with different payload.
- **Response**: HTTP 200 for duplicates, HTTP 202 (Accepted) for new requests.

```
POST /api/v1/payments/deposit
Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000
```

### 2. Concurrency Handling

**Optimistic Locking (default)**
```java
@Version
private Long version;  // Hibernate increments on every UPDATE
```
- Zero DB locks held during processing.
- `OptimisticLockingFailureException` on conflict → automatic retry with backoff (50ms, 100ms, 150ms).
- Best for regular user wallets with low contention.

**Deadlock Prevention**
```java
// Always lock wallets in UUID order — prevents circular wait
if (senderId.compareTo(receiverId) < 0) {
    first  = lock(senderId);
    second = lock(receiverId);
} else {
    first  = lock(receiverId);
    second = lock(senderId);
}
```

**Pessimistic Locking (available)**
```java
// Switch to this for high-contention wallets (merchant accounts)
walletRepository.findByIdWithPessimisticLock(walletId)
// → SELECT ... FOR UPDATE
```

### 3. Transaction State Machine
```
PENDING ──► PROCESSING ──► SUCCESS
                       └──► FAILED ──► ROLLED_BACK
                                  └──► DEAD_LETTERED
```

| State          | Meaning                                           |
|----------------|---------------------------------------------------|
| PENDING        | Created, queued for processing                    |
| PROCESSING     | Consumer picked up, in-flight                     |
| SUCCESS        | Money moved, ledger entries written               |
| FAILED         | Business or technical failure, retryable          |
| ROLLED_BACK    | Compensating entries written, money restored      |
| DEAD_LETTERED  | Max retries exhausted, manual intervention needed |

### 4. Async Processing + DLQ

**Queue topology:**
```
payment.exchange ──[routing: payment]──► payment.processing
payment.exchange ──[routing: retry]───► payment.retry (TTL: 5s)
                                              │
                                         [TTL expires]
                                              │
                                              ▼
                                        payment.exchange ──► payment.processing

payment.processing ──[x-dead-letter]──► payment.dead-letter.exchange
                                                │
                                                ▼
                                        payment.dead-letter (DLQ)
```

**Retry strategy:**
1. On retryable failure → increment `retry_count`, send to `payment.retry` queue
2. After 5s TTL, message re-routes back to `payment.processing`
3. After `maxRetryAttempts` (3), message goes to DLQ
4. DLQ consumer marks transaction as `DEAD_LETTERED`

### 5. Failure Handling + Rollback

Both wallet operations (debit + credit) execute in a single `@Transactional` boundary:
- If sender debit succeeds but receiver credit fails → entire DB transaction rolls back atomically.
- No partial state. No money lost. No manual reconciliation needed.

For compensation scenarios (e.g., external system failure after DB commit):
```java
ledgerService.recordCompensation(wallet, transaction, amount, LedgerEntryType.DEBIT);
```

### 6. Immutable Double-Entry Ledger

Every financial event creates two `LedgerEntry` records:

```
Transfer ₹500: Alice → Bob

LedgerEntry #1: wallet=Alice, type=DEBIT,  amount=500, runningBalance=4500
LedgerEntry #2: wallet=Bob,   type=CREDIT, amount=500, runningBalance=1500
```

Properties:
- Marked `@Immutable` (Hibernate never issues UPDATE/DELETE)
- `runningBalance` = wallet balance at time of entry (point-in-time reconstruction)
- Corrections use **compensating entries**, never edits
- Enables: full audit trail, balance verification, fraud detection

---

## Quick Start

### 1. Start infrastructure
```bash
docker-compose up -d
# PostgreSQL :5432 | Redis :6379 | RabbitMQ :5672 (UI: :15672)
```

### 2. Run application
```bash
./mvnw spring-boot:run
# H2 console (dev): http://localhost:8080/h2-console
# Swagger UI:       http://localhost:8080/swagger-ui.html
# RabbitMQ UI:      http://localhost:15672 (guest/guest)
```

### 3. API Walkthrough

**Register a user:**
```bash
curl -X POST http://localhost:8080/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","email":"alice@test.com","password":"password123","fullName":"Alice Smith"}'
```

**Deposit money:**
```bash
curl -X POST http://localhost:8080/api/v1/payments/deposit \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"amount": 5000.00, "description": "Initial deposit"}'
```

**Transfer to another user:**
```bash
curl -X POST http://localhost:8080/api/v1/payments/transfer \
  -H "Authorization: Bearer <token>" \
  -H "Idempotency-Key: $(uuidgen)" \
  -H "Content-Type: application/json" \
  -d '{"receiverWalletNumber":"WL-XXXXXXXXXX","amount":500.00,"description":"Rent"}'
```

**Get ledger statement:**
```bash
curl "http://localhost:8080/api/v1/ledger/statement?from=2025-01-01T00:00:00&to=2025-12-31T23:59:59" \
  -H "Authorization: Bearer <token>"
```

---

## Project Structure

```
src/main/java/com/fintech/payment/
├── PaymentSystemApplication.java
├── config/
│   ├── RabbitMQConfig.java         # Queue topology (DLQ, retry)
│   ├── RedisConfig.java            # Idempotency cache
│   ├── SecurityConfig.java         # JWT stateless auth
│   ├── JwtService.java
│   ├── JwtAuthenticationFilter.java
│   └── OpenApiConfig.java
├── controller/
│   ├── AuthController.java
│   ├── WalletController.java
│   ├── PaymentController.java      # Idempotency-Key header
│   └── LedgerController.java
├── service/
│   ├── IdempotencyService.java     # Two-layer idempotency
│   ├── LedgerService.java          # Double-entry bookkeeping
│   ├── PaymentProcessor.java       # Atomic execution + lock retry
│   ├── PaymentQueueProducer.java
│   └── impl/
│       ├── AuthService.java
│       ├── WalletService.java
│       ├── PaymentService.java     # Orchestration layer
│       └── UserDetailsServiceImpl.java
├── consumer/
│   └── PaymentQueueConsumer.java   # Async worker + DLQ handler
├── scheduler/
│   └── PaymentRetryScheduler.java  # Retry + cleanup jobs
├── entity/
│   ├── User.java
│   ├── Wallet.java                 # @Version optimistic lock
│   ├── Transaction.java            # State machine
│   ├── LedgerEntry.java            # @Immutable double-entry
│   └── IdempotencyRecord.java
├── repository/                     # Pessimistic + optimistic lock queries
├── dto/                            # Request/response/queue message objects
├── enums/                          # TransactionStatus, Type, LedgerEntryType
└── exception/                      # Domain exceptions + GlobalExceptionHandler
```

---

## Key Design Decisions

| Decision | Approach | Why |
|----------|----------|-----|
| Concurrency | Optimistic locking default | No DB locks = higher throughput |
| Idempotency | Redis + PostgreSQL | Fast + durable |
| Async | RabbitMQ with TTL-based retry | Decoupled, retryable, observable |
| Atomicity | Single `@Transactional` boundary | No partial transfer state |
| Audit | Immutable ledger with running balance | Tamper-evident, point-in-time query |
| Auth | Stateless JWT | Horizontally scalable |

---

## Production Checklist

- [ ] Switch `application-prod.properties` and set env vars
- [ ] Enable Flyway migrations (replace `ddl-auto=create-drop`)
- [ ] Add rate limiting (e.g., Bucket4j)
- [ ] Set up RabbitMQ management alerts for DLQ depth
- [ ] Add distributed tracing (Micrometer + Zipkin)
- [ ] Configure Redis Sentinel / Cluster for HA
- [ ] Replace `RejectAndDontRequeueRecoverer` with custom alerting recoverer
