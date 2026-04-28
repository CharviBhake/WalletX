package com.fintech.payment.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * DB-backed idempotency record.
 *
 * Two-layer idempotency strategy:
 * Layer 1 (Fast): Redis cache — O(1) lookup, TTL-based expiry.
 * Layer 2 (Durable): This DB table — survives Redis flush/restart.
 *
 * On request:
 * 1. Check Redis → if hit, return cached response.
 * 2. Check DB    → if hit, return stored response.
 * 3. Process     → store result in both Redis and DB.
 */
@Entity
@Table(name = "idempotency_records",
       indexes = {
           @Index(name = "idx_idempotency_key", columnList = "idempotency_key", unique = true)
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "idempotency_key", unique = true, nullable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "request_hash", length = 64)
    private String requestHash;   // SHA-256 of request body to detect misuse

    @Column(name = "response_status", length = 20)
    private String responseStatus;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
