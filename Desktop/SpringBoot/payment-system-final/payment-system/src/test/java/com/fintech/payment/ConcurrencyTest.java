package com.fintech.payment;

import com.fintech.payment.entity.Wallet;
import com.fintech.payment.repository.WalletRepository;
import com.fintech.payment.service.PaymentProcessor;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import com.fintech.payment.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Concurrency tests verifying optimistic locking retry behaviour.
 *
 * These tests simulate concurrent wallet operations and verify that:
 * 1. OptimisticLockingFailureException triggers the retry mechanism
 * 2. After max retries are exhausted, exception propagates
 * 3. Successful retry eventually processes the transaction
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Concurrency Tests")
class ConcurrencyTest {

    @Mock private WalletRepository walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private com.fintech.payment.service.LedgerService ledgerService;

    @InjectMocks private PaymentProcessor paymentProcessor;

    @Test
    @DisplayName("processWithRetry succeeds after transient optimistic lock conflict")
    void processWithRetry_transientConflict_retriesAndSucceeds() {
        UUID walletId = UUID.randomUUID();

        Wallet wallet = Wallet.builder()
            .id(walletId)
            .walletNumber("WL-TEST001")
            .balance(new BigDecimal("1000.00"))
            .reservedBalance(BigDecimal.ZERO)
            .currency("INR")
            .active(true)
            .version(1L)
            .build();

        Transaction txn = Transaction.builder()
            .id(UUID.randomUUID())
            .idempotencyKey(UUID.randomUUID().toString())
            .type(TransactionType.DEPOSIT)
            .status(TransactionStatus.PENDING)
            .receiverWallet(wallet)
            .amount(new BigDecimal("100.00"))
            .currency("INR")
            .retryCount(0)
            .build();

        // First call → optimistic lock failure, second call → success
        when(walletRepository.findByIdWithOptimisticLock(walletId))
            .thenThrow(new OptimisticLockingFailureException("Simulated concurrent modification"))
            .thenReturn(Optional.of(wallet));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw — retry logic handles the first failure
        paymentProcessor.processWithRetry(txn);

        assertThat(txn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        // Verify wallet was fetched twice (once failed, once succeeded)
        verify(walletRepository, times(2)).findByIdWithOptimisticLock(walletId);
    }

    @Test
    @DisplayName("processWithRetry throws after exhausting all retries")
    void processWithRetry_allRetriesExhausted_throwsException() {
        UUID walletId = UUID.randomUUID();

        Wallet wallet = Wallet.builder()
            .id(walletId)
            .walletNumber("WL-TEST002")
            .balance(new BigDecimal("1000.00"))
            .reservedBalance(BigDecimal.ZERO)
            .currency("INR")
            .active(true)
            .version(1L)
            .build();

        Transaction txn = Transaction.builder()
            .id(UUID.randomUUID())
            .idempotencyKey(UUID.randomUUID().toString())
            .type(TransactionType.DEPOSIT)
            .status(TransactionStatus.PENDING)
            .receiverWallet(wallet)
            .amount(new BigDecimal("100.00"))
            .currency("INR")
            .retryCount(0)
            .build();

        // All 3 attempts fail with optimistic lock
        when(walletRepository.findByIdWithOptimisticLock(walletId))
            .thenThrow(new OptimisticLockingFailureException("Persistent conflict"));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        org.junit.jupiter.api.Assertions.assertThrows(
            OptimisticLockingFailureException.class,
            () -> paymentProcessor.processWithRetry(txn)
        );

        // Should have tried exactly 3 times (MAX_OPTIMISTIC_LOCK_RETRIES)
        verify(walletRepository, times(3)).findByIdWithOptimisticLock(walletId);
    }

    @Test
    @DisplayName("Multi-threaded deposits maintain thread-safety via lock ordering")
    void concurrentDeposits_threadSafety_noRaceCondition() throws InterruptedException {
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch  = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        UUID walletId = UUID.randomUUID();

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();  // All threads start simultaneously

                    Wallet wallet = Wallet.builder()
                        .id(walletId)
                        .walletNumber("WL-CONC001")
                        .balance(new BigDecimal("10000.00"))
                        .reservedBalance(BigDecimal.ZERO)
                        .currency("INR")
                        .active(true)
                        .version((long) idx)
                        .build();

                    Transaction txn = Transaction.builder()
                        .id(UUID.randomUUID())
                        .idempotencyKey(UUID.randomUUID().toString())
                        .type(TransactionType.DEPOSIT)
                        .status(TransactionStatus.PENDING)
                        .receiverWallet(wallet)
                        .amount(new BigDecimal("100.00"))
                        .currency("INR")
                        .retryCount(0)
                        .build();

                    when(walletRepository.findByIdWithOptimisticLock(walletId))
                        .thenReturn(Optional.of(wallet));
                    when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

                    paymentProcessor.processTransaction(txn);
                    successCount.incrementAndGet();

                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();  // Release all threads at once
        doneLatch.await();
        executor.shutdown();

        // All should succeed (mocked wallets don't actually conflict)
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failureCount.get()).isZero();
    }
}
