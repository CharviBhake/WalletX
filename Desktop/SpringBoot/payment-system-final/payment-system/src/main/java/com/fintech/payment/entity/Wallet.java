package com.fintech.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Wallet entity.
 *
 * CONCURRENCY STRATEGY:
 * - @Version enables Optimistic Locking (OCC).
 * - On concurrent updates, Hibernate throws OptimisticLockException.
 * - Service layer catches this and retries with backoff.
 *
 * For high-contention wallets (e.g., merchant accounts),
 * switch to SELECT FOR UPDATE (pessimistic locking) via
 * @Lock(LockModeType.PESSIMISTIC_WRITE) on the repository query.
 */
@Entity
@Table(name = "wallets",
       indexes = {
           @Index(name = "idx_wallet_user_id", columnList = "user_id"),
           @Index(name = "idx_wallet_wallet_number", columnList = "wallet_number")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "wallet_number", unique = true, nullable = false, length = 20)
    private String walletNumber;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "reserved_balance", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal reservedBalance = BigDecimal.ZERO;   // For in-flight transfers

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private boolean active = true;

    /**
     * Optimistic Locking version field.
     * Incremented by Hibernate on every UPDATE.
     * Concurrent modification → OptimisticLockException.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // ──────────────────────────────────────────────
    // Business logic helpers
    // ──────────────────────────────────────────────

    public BigDecimal getAvailableBalance() {
        return balance.subtract(reservedBalance);
    }

    public boolean hasSufficientBalance(BigDecimal amount) {
        return getAvailableBalance().compareTo(amount) >= 0;
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public void debit(BigDecimal amount) {
        if (!hasSufficientBalance(amount)) {
            throw new IllegalStateException("Insufficient balance");
        }
        this.balance = this.balance.subtract(amount);
    }

    public void reserve(BigDecimal amount) {
        if (!hasSufficientBalance(amount)) {
            throw new IllegalStateException("Insufficient available balance to reserve");
        }
        this.reservedBalance = this.reservedBalance.add(amount);
    }

    public void releaseReservation(BigDecimal amount) {
        this.reservedBalance = this.reservedBalance.subtract(amount);
        if (this.reservedBalance.compareTo(BigDecimal.ZERO) < 0) {
            this.reservedBalance = BigDecimal.ZERO;
        }
    }
}
