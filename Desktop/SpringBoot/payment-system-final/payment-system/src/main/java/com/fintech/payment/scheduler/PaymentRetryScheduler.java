package com.fintech.payment.scheduler;

import com.fintech.payment.entity.Transaction;
import com.fintech.payment.repository.IdempotencyRecordRepository;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.service.PaymentQueueProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled jobs for payment system maintenance.
 *
 * JOB 1 — Retry stale FAILED transactions
 *   Runs every 5 minutes. Picks up FAILED transactions that:
 *   - Have retry count below the max
 *   - Haven't been retried recently (updated_at > retry window)
 *   Re-enqueues them to the payment processing queue.
 *
 * JOB 2 — Expire stuck PENDING transactions
 *   Transactions stuck in PENDING/PROCESSING for > 10 minutes
 *   are likely orphaned (consumer crashed mid-flight). Mark them FAILED.
 *
 * JOB 3 — Clean expired idempotency records
 *   Purges DB records past their TTL. Redis TTL handles its own expiry.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
public class PaymentRetryScheduler {

    private final TransactionRepository        transactionRepository;
    private final IdempotencyRecordRepository  idempotencyRecordRepository;
    private final PaymentQueueProducer         queueProducer;

    @Value("${app.payment.max-retry-attempts:3}")
    private int maxRetryAttempts;

    // ─── Job 1: Re-queue retryable FAILED transactions ───────────────────────

    @Scheduled(fixedDelay = 300_000)  // Every 5 minutes
    @Transactional
    public void retryFailedTransactions() {
        LocalDateTime retryBefore = LocalDateTime.now().minusMinutes(5);
        List<Transaction> retryable = transactionRepository
            .findRetryableTransactions(maxRetryAttempts, retryBefore);

        if (retryable.isEmpty()) return;

        log.info("Retry scheduler: found {} retryable transactions", retryable.size());

        for (Transaction txn : retryable) {
            try {
                log.info("Re-queuing transaction: txnId={}, retryCount={}",
                    txn.getId(), txn.getRetryCount());
                queueProducer.enqueuePayment(txn);
            } catch (Exception e) {
                log.error("Failed to re-queue transaction {}: {}", txn.getId(), e.getMessage());
            }
        }
    }

    // ─── Job 2: Expire stuck PENDING / PROCESSING transactions ───────────────

    @Scheduled(fixedDelay = 600_000)  // Every 10 minutes
    @Transactional
    public void expireStuckTransactions() {
        LocalDateTime stuckThreshold = LocalDateTime.now().minusMinutes(10);

        List<Transaction> stuck = transactionRepository
            .findByStatusOrderByCreatedAtAsc(com.fintech.payment.enums.TransactionStatus.PENDING)
            .stream()
            .filter(t -> t.getCreatedAt().isBefore(stuckThreshold))
            .toList();

        List<Transaction> stuckProcessing = transactionRepository
            .findByStatusOrderByCreatedAtAsc(com.fintech.payment.enums.TransactionStatus.PROCESSING)
            .stream()
            .filter(t -> t.getProcessedAt() != null && t.getProcessedAt().isBefore(stuckThreshold))
            .toList();

        int count = 0;
        for (Transaction txn : stuck) {
            txn.markFailed("Expired: stuck in PENDING state for > 10 minutes");
            transactionRepository.save(txn);
            count++;
        }
        for (Transaction txn : stuckProcessing) {
            txn.markFailed("Expired: stuck in PROCESSING state for > 10 minutes");
            transactionRepository.save(txn);
            count++;
        }

        if (count > 0) {
            log.warn("Expiry scheduler: marked {} stuck transactions as FAILED", count);
        }
    }

    // ─── Job 3: Purge expired idempotency records ─────────────────────────────

    @Scheduled(cron = "0 0 2 * * *")  // Daily at 2 AM
    @Transactional
    public void cleanExpiredIdempotencyRecords() {
        int deleted = idempotencyRecordRepository.deleteExpiredRecords(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Idempotency cleanup: deleted {} expired records", deleted);
        }
    }
}
