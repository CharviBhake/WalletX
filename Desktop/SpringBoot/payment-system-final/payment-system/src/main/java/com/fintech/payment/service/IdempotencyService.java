package com.fintech.payment.service;

import com.fintech.payment.dto.TransactionResponse;
import com.fintech.payment.entity.IdempotencyRecord;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.exception.PaymentException;
import com.fintech.payment.repository.IdempotencyRecordRepository;
import com.fintech.payment.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Two-layer Idempotency Service.
 *
 * WHY TWO LAYERS?
 * - Redis (L1): Sub-millisecond lookup, handles 99% of duplicate traffic.
 *   TTL auto-expires keys. Downside: volatile — data lost on flush.
 * - Database (L2): Durable record that survives Redis restarts.
 *   Acts as the source of truth during Redis recovery.
 *
 * Flow on duplicate request:
 *   1. Check Redis → HIT → return cached TransactionResponse immediately
 *   2. Check DB    → HIT → repopulate Redis + return response
 *   3. MISS on both → allow processing, record in both after completion
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String REDIS_PREFIX = "idempotency:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionRepository transactionRepository;

    @Value("${app.idempotency.ttl-hours:24}")
    private long ttlHours;

    /**
     * Check if this idempotency key has already been processed.
     *
     * @param key           The client-provided idempotency key
     * @param requestHash   SHA-256 of the request body (to detect key reuse with different payload)
     * @return Optional with existing TransactionResponse if duplicate, empty if new
     */
    public Optional<TransactionResponse> checkIdempotency(String key, String requestHash) {
        // L1: Redis cache
        String redisKey = REDIS_PREFIX + key;
        Object cached = redisTemplate.opsForValue().get(redisKey);
        if (cached instanceof TransactionResponse response) {
            log.debug("Idempotency cache HIT (Redis) for key: {}", key);
            response.setDuplicate(true);
            return Optional.of(response);
        }

        // L2: Database
        Optional<IdempotencyRecord> record = idempotencyRecordRepository.findByIdempotencyKey(key);
        if (record.isPresent()) {
            IdempotencyRecord idempotencyRecord = record.get();

            // Guard: same key, different request body = client bug
            if (requestHash != null && !requestHash.equals(idempotencyRecord.getRequestHash())) {
                throw new PaymentException(
                    "Idempotency key '" + key + "' was used with a different request payload",
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "IDEMPOTENCY_KEY_REUSE"
                );
            }

            // Re-fetch transaction and rebuild response
            Transaction txn = transactionRepository.findById(idempotencyRecord.getTransactionId())
                .orElseThrow(() -> new PaymentException(
                    "Idempotency record found but transaction missing for key: " + key,
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "IDEMPOTENCY_INCONSISTENCY"
                ));

            TransactionResponse response = toResponse(txn, true);

            // Repopulate Redis cache
            redisTemplate.opsForValue().set(redisKey, response, Duration.ofHours(ttlHours));
            log.debug("Idempotency cache HIT (DB) for key: {}, repopulated Redis", key);

            return Optional.of(response);
        }

        return Optional.empty();
    }

    /**
     * Record a completed transaction as idempotent.
     * Called after successful payment processing.
     */
    @Transactional
    public void recordIdempotency(String key, String requestHash, Transaction transaction) {
        // Save to DB
        IdempotencyRecord record = IdempotencyRecord.builder()
            .idempotencyKey(key)
            .transactionId(transaction.getId())
            .requestHash(requestHash)
            .responseStatus(transaction.getStatus().name())
            .expiresAt(LocalDateTime.now().plusHours(ttlHours))
            .build();

        idempotencyRecordRepository.save(record);

        // Cache in Redis
        String redisKey = REDIS_PREFIX + key;
        TransactionResponse response = toResponse(transaction, false);
        redisTemplate.opsForValue().set(redisKey, response, Duration.ofHours(ttlHours));

        log.debug("Recorded idempotency for key: {}, txnId: {}", key, transaction.getId());
    }

    /**
     * Compute SHA-256 hash of request body for payload fingerprinting.
     */
    public String computeHash(String requestBody) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(requestBody.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            log.warn("Failed to compute request hash", e);
            return null;
        }
    }

    private TransactionResponse toResponse(Transaction txn, boolean isDuplicate) {
        return TransactionResponse.builder()
            .id(txn.getId())
            .idempotencyKey(txn.getIdempotencyKey())
            .type(txn.getType())
            .status(txn.getStatus())
            .amount(txn.getAmount())
            .currency(txn.getCurrency())
            .description(txn.getDescription())
            .referenceId(txn.getReferenceId())
            .senderWalletNumber(txn.getSenderWallet() != null
                ? txn.getSenderWallet().getWalletNumber() : null)
            .receiverWalletNumber(txn.getReceiverWallet() != null
                ? txn.getReceiverWallet().getWalletNumber() : null)
            .retryCount(txn.getRetryCount())
            .failureReason(txn.getFailureReason())
            .createdAt(txn.getCreatedAt())
            .processedAt(txn.getProcessedAt())
            .completedAt(txn.getCompletedAt())
            .duplicate(isDuplicate)
            .build();
    }
}
