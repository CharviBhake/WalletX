package com.fintech.payment;

import com.fintech.payment.consumer.PaymentQueueConsumer;
import com.fintech.payment.dto.PaymentQueueMessage;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import com.fintech.payment.exception.InsufficientBalanceException;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.service.PaymentProcessor;
import com.fintech.payment.service.PaymentQueueProducer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentQueueConsumer Tests")
class PaymentQueueConsumerTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private PaymentProcessor      paymentProcessor;
    @Mock private PaymentQueueProducer  queueProducer;

    @InjectMocks private PaymentQueueConsumer consumer;

    private Transaction buildTransaction(TransactionStatus status) {
        Wallet wallet = Wallet.builder()
            .id(UUID.randomUUID()).walletNumber("WL-TEST001")
            .balance(BigDecimal.valueOf(1000)).reservedBalance(BigDecimal.ZERO)
            .currency("INR").active(true).version(1L).build();

        return Transaction.builder()
            .id(UUID.randomUUID())
            .idempotencyKey(UUID.randomUUID().toString())
            .type(TransactionType.DEPOSIT)
            .status(status)
            .receiverWallet(wallet)
            .amount(BigDecimal.valueOf(100))
            .currency("INR")
            .retryCount(0)
            .build();
    }

    private PaymentQueueMessage buildMessage(UUID txnId) {
        return PaymentQueueMessage.builder()
            .transactionId(txnId)
            .type(TransactionType.DEPOSIT)
            .amount(BigDecimal.valueOf(100))
            .currency("INR")
            .retryCount(0)
            .enqueuedAt(LocalDateTime.now())
            .build();
    }

    @Test
    @DisplayName("Successfully processed transaction is not re-enqueued")
    void processPayment_success_noRetry() {
        Transaction txn = buildTransaction(TransactionStatus.PENDING);
        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        doNothing().when(paymentProcessor).processWithRetry(txn);

        consumer.processPayment(buildMessage(txn.getId()));

        verify(paymentProcessor).processWithRetry(txn);
        verifyNoInteractions(queueProducer);
    }

    @Test
    @DisplayName("Terminal-state transaction is skipped without processing")
    void processPayment_alreadySuccess_skipped() {
        Transaction txn = buildTransaction(TransactionStatus.SUCCESS);
        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));

        consumer.processPayment(buildMessage(txn.getId()));

        verifyNoInteractions(paymentProcessor);
        verifyNoInteractions(queueProducer);
    }

    @Test
    @DisplayName("Non-retryable failure (InsufficientBalance) marks FAILED without retry")
    void processPayment_insufficientBalance_marksFailedNoRetry() {
        Transaction txn = buildTransaction(TransactionStatus.PENDING);
        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new InsufficientBalanceException("WL-TEST001", BigDecimal.valueOf(50), BigDecimal.valueOf(100)))
            .when(paymentProcessor).processWithRetry(txn);

        consumer.processPayment(buildMessage(txn.getId()));

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.FAILED);
        verifyNoInteractions(queueProducer);  // Not re-enqueued
    }

    @Test
    @DisplayName("Retryable failure below max retries re-enqueues to retry queue")
    void processPayment_retryableFailure_enqueuesToRetryQueue() {
        Transaction txn = buildTransaction(TransactionStatus.PENDING);
        when(transactionRepository.findById(txn.getId())).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Transient DB error"))
            .when(paymentProcessor).processWithRetry(txn);

        // Set maxRetryAttempts via reflection
        org.springframework.test.util.ReflectionTestUtils.setField(consumer, "maxRetryAttempts", 3);

        consumer.processPayment(buildMessage(txn.getId()));

        assertThat(txn.getRetryCount()).isEqualTo(1);
        verify(queueProducer).enqueueRetry(any());
    }

    @Test
    @DisplayName("DLQ consumer marks transaction as DEAD_LETTERED")
    void handleDeadLetter_marksDeadLettered() {
        Transaction txn = buildTransaction(TransactionStatus.FAILED);
        txn.setFailureReason("Repeated lock failures");
        UUID txnId = txn.getId();

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentQueueMessage dlqMessage = buildMessage(txnId);
        dlqMessage.setRetryCount(3);

        consumer.handleDeadLetter(dlqMessage);

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.DEAD_LETTERED);
        assertThat(txn.getFailureReason()).contains("Exhausted all retries");
    }
}
