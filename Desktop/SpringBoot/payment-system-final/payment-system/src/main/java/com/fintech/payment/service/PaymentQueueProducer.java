package com.fintech.payment.service;

import com.fintech.payment.dto.PaymentQueueMessage;
import com.fintech.payment.entity.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Publishes payment processing messages to RabbitMQ.
 *
 * Two routing keys:
 * - "payment" → main processing queue (new + requeued messages)
 * - "retry"   → delayed retry queue (TTL before re-routing back to main)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentQueueProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${app.queue.exchange}")
    private String exchange;

    /**
     * Enqueue a transaction for async processing.
     */
    public void enqueuePayment(Transaction transaction) {
        PaymentQueueMessage message = PaymentQueueMessage.builder()
            .transactionId(transaction.getId())
            .type(transaction.getType())
            .senderWalletId(transaction.getSenderWallet() != null
                ? transaction.getSenderWallet().getId() : null)
            .receiverWalletId(transaction.getReceiverWallet() != null
                ? transaction.getReceiverWallet().getId() : null)
            .amount(transaction.getAmount())
            .currency(transaction.getCurrency())
            .retryCount(transaction.getRetryCount())
            .enqueuedAt(LocalDateTime.now())
            .build();

        rabbitTemplate.convertAndSend(exchange, "payment", message);
        log.info("Enqueued payment: txnId={}, type={}, amount={}",
            transaction.getId(), transaction.getType(), transaction.getAmount());
    }

    /**
     * Send a message to the retry queue (delayed delivery via TTL).
     */
    public void enqueueRetry(PaymentQueueMessage message) {
        message.setRetryCount(message.getRetryCount() + 1);
        message.setEnqueuedAt(LocalDateTime.now());
        rabbitTemplate.convertAndSend(exchange, "retry", message);
        log.warn("Enqueued retry #{} for txnId={}", message.getRetryCount(), message.getTransactionId());
    }
}
