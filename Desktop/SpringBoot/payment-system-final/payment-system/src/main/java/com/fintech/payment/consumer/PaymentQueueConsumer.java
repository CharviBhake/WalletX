package com.fintech.payment.consumer;

import com.fintech.payment.dto.PaymentQueueMessage;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.exception.InsufficientBalanceException;
import com.fintech.payment.exception.TransactionNotFoundException;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.service.PaymentProcessor;
import com.fintech.payment.service.PaymentQueueProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/**
 * Async Payment Queue Consumer.
 *
 * PROCESSING FLOW:
 *   1. Receive message from payment.processing queue
 *   2. Load transaction from DB (verify it's still PENDING/FAILED)
 *   3. Execute via PaymentProcessor (with optimistic lock retry)
 *   4. On success → transaction marked SUCCESS
 *   5. On retryable failure → increment retry count, send to retry queue
 *   6. On non-retryable failure (e.g., InsufficientBalance) → mark FAILED, don't retry
 *   7. After max retries → message goes to DLQ via RabbitMQ DLX routing
 *
 * DEAD LETTER QUEUE:
 *   Messages that exhaust Spring AMQP's built-in retry (3 attempts with backoff)
 *   are automatically routed to payment.dead-letter via the DLX.
 *   Separate @RabbitListener on the DLQ marks transactions as DEAD_LETTERED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentQueueConsumer {

    private final TransactionRepository transactionRepository;
    private final PaymentProcessor      paymentProcessor;
    private final PaymentQueueProducer  queueProducer;

    @Value("${app.payment.max-retry-attempts:3}")
    private int maxRetryAttempts;

    // ─── Main consumer ────────────────────────────────────────────────────────

    @RabbitListener(queues = "${app.queue.payment-processing}")
    public void processPayment(PaymentQueueMessage message) {
        log.info("Processing payment: txnId={}, type={}, attempt={}",
            message.getTransactionId(), message.getType(), message.getRetryCount() + 1);

        Transaction txn = transactionRepository.findById(message.getTransactionId())
            .orElseThrow(() -> {
                log.error("Transaction not found in queue consumer: {}", message.getTransactionId());
                return new TransactionNotFoundException(message.getTransactionId());
            });

        // Skip if already completed (idempotent consumer)
        if (isTerminalState(txn)) {
            log.warn("Transaction {} already in terminal state: {}. Skipping.",
                txn.getId(), txn.getStatus());
            return;
        }

        try {
            paymentProcessor.processWithRetry(txn);
            log.info("Payment processed successfully: txnId={}", txn.getId());

        } catch (InsufficientBalanceException | com.fintech.payment.exception.WalletInactiveException e) {
            // Non-retryable — don't waste retries on business logic failures
            log.warn("Non-retryable payment failure: txnId={}, reason={}", txn.getId(), e.getMessage());
            txn.markFailed(e.getMessage());
            transactionRepository.save(txn);
            // Do NOT rethrow — message is acknowledged and discarded

        } catch (OptimisticLockingFailureException e) {
            // Retryable — concurrent modification, will resolve on retry
            handleRetryableFailure(txn, message, "Optimistic lock conflict: " + e.getMessage());

        } catch (Exception e) {
            // Retryable — unknown error, attempt retry
            handleRetryableFailure(txn, message, e.getMessage());
        }
    }

    // ─── Dead Letter Queue consumer ───────────────────────────────────────────

    /**
     * Listens on the Dead Letter Queue.
     * Messages arrive here after exhausting all RabbitMQ-level retries.
     * We mark the transaction as DEAD_LETTERED for ops visibility.
     */
    @RabbitListener(queues = "${app.queue.payment-dlq}")
    public void handleDeadLetter(PaymentQueueMessage message) {
        log.error("Payment reached DLQ: txnId={}, retryCount={}",
            message.getTransactionId(), message.getRetryCount());

        transactionRepository.findById(message.getTransactionId()).ifPresent(txn -> {
            if (!isTerminalState(txn)) {
                txn.markDeadLettered(
                    "Exhausted all retries (" + message.getRetryCount() + "). " +
                    "Manual intervention required. Failure: " + txn.getFailureReason()
                );
                transactionRepository.save(txn);
                log.error("Transaction {} moved to DEAD_LETTERED state", txn.getId());
            }
        });

        // Alert / notification hook — in production you'd publish to an alerting system here
        // alertService.sendDeadLetterAlert(message);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void handleRetryableFailure(Transaction txn, PaymentQueueMessage message, String reason) {
        txn.incrementRetry();
        txn.markFailed(reason);
        transactionRepository.save(txn);

        if (txn.getRetryCount() < maxRetryAttempts) {
            log.warn("Retryable failure for txnId={}, attempt={}/{}, reason={}",
                txn.getId(), txn.getRetryCount(), maxRetryAttempts, reason);
            // Send to delayed retry queue (TTL-based backoff)
            queueProducer.enqueueRetry(message);
        } else {
            log.error("Max retries ({}) exhausted for txnId={}", maxRetryAttempts, txn.getId());
            // Let it become FAILED — DLX handles DLQ routing at RabbitMQ level
            // when the Spring AMQP RetryInterceptor finally calls RejectAndDontRequeueRecoverer
            throw new RuntimeException("Max retries exceeded for txnId=" + txn.getId());
        }
    }

    private boolean isTerminalState(Transaction txn) {
        return switch (txn.getStatus()) {
            case SUCCESS, ROLLED_BACK, DEAD_LETTERED -> true;
            default -> false;
        };
    }
}
