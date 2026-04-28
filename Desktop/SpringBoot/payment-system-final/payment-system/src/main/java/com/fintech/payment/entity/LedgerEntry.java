package com.fintech.payment.entity;

import com.fintech.payment.enums.LedgerEntryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable Ledger Entry — the heart of fintech-grade accounting.
 *
 * PRINCIPLES:
 * 1. Double-entry: Every transaction creates TWO ledger entries
 *    (DEBIT on sender, CREDIT on receiver).
 * 2. Immutability: Entries are NEVER updated or deleted.
 *    Corrections are made via compensating entries.
 * 3. Audit trail: running_balance captures the wallet balance
 *    AT THE TIME of the entry — perfect for reconciliation.
 *
 * This gives you: full audit history, balance reconstruction
 * at any point in time, and tamper detection.
 */
@Entity
@Immutable   // Hibernate hint: this entity is never updated
@Table(name = "ledger_entries",
       indexes = {
           @Index(name = "idx_ledger_wallet_id",      columnList = "wallet_id"),
           @Index(name = "idx_ledger_transaction_id", columnList = "transaction_id"),
           @Index(name = "idx_ledger_created_at",     columnList = "created_at")
       })
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false, updatable = false)
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 10, updatable = false)
    private LedgerEntryType entryType;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    /**
     * Snapshot of wallet balance AFTER this entry was applied.
     * Enables point-in-time balance reconstruction and fraud detection.
     */
    @Column(name = "running_balance", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal runningBalance;

    @Column(name = "description", length = 500, updatable = false)
    private String description;

    @Column(name = "reference_id", length = 100, updatable = false)
    private String referenceId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
