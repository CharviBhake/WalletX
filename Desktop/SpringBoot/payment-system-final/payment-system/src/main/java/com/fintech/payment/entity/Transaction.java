package com.fintech.payment.entity;

import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Transaction entity.
 *
 * STATE MACHINE:
 *   PENDING → PROCESSING → SUCCESS
 *                       → FAILED → ROLLED_BACK
 *                       → DEAD_LETTERED (after max DLQ retries)
 *
 * IDEMPOTENCY:
 *   idempotencyKey is a unique constraint. Duplicate requests with
 *   the same key return the existing transaction without re-processing.
 */
@Entity
@Table(name = "transactions",
       indexes = {
           @Index(name = "idx_txn_idempotency_key", columnList = "idempotency_key", unique = true),
           @Index(name = "idx_txn_sender_wallet",   columnList = "sender_wallet_id"),
           @Index(name = "idx_txn_receiver_wallet", columnList = "receiver_wallet_id"),
           @Index(name = "idx_txn_status",          columnList = "status"),
           @Index(name = "idx_txn_created_at",      columnList = "created_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /**
     * Client-provided idempotency key.
     * Stored with a unique constraint to prevent duplicate processing.
     */
    @Column(name = "idempotency_key", unique = true, nullable = false, length = 100)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_wallet_id")
    private Wallet senderWallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_wallet_id")
    private Wallet receiverWallet;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "reference_id", length = 100)
    private String referenceId;  // External reference (e.g., order ID)

    // ── Retry / DLQ tracking ──────────────────────────────
    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ─────────────────────────────────────────────────────
    // State transition helpers
    // ─────────────────────────────────────────────────────

    public void markProcessing() {
        this.status = TransactionStatus.PROCESSING;
        this.processedAt = LocalDateTime.now();
    }

    public void markSuccess() {
        this.status = TransactionStatus.SUCCESS;
        this.completedAt = LocalDateTime.now();
    }

    public void markFailed(String reason) {
        this.status = TransactionStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = LocalDateTime.now();
    }

    public void markRolledBack() {
        this.status = TransactionStatus.ROLLED_BACK;
    }

    public void markDeadLettered(String reason) {
        this.status = TransactionStatus.DEAD_LETTERED;
        this.failureReason = reason;
    }

    public void incrementRetry() {
        this.retryCount++;
    }
}
