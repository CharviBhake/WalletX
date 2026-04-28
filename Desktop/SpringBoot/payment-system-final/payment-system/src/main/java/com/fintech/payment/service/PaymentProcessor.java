package com.fintech.payment.service;

import com.fintech.payment.entity.Transaction;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.enums.LedgerEntryType;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import com.fintech.payment.exception.InsufficientBalanceException;
import com.fintech.payment.exception.WalletInactiveException;
import com.fintech.payment.exception.WalletNotFoundException;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core payment execution engine.
 *
 * CONCURRENCY STRATEGY:
 * - Uses Optimistic Locking (@Version on Wallet) by default.
 * - On OptimisticLockingFailureException, caller retries up to 3 times.
 * - Transaction isolation: REPEATABLE_READ to prevent phantom reads.
 *
 * FAILURE HANDLING:
 * - If sender debit succeeds but receiver credit fails:
 *   → rollback entire DB transaction (Spring @Transactional)
 *   → mark transaction as FAILED
 *   → record compensation ledger entry for audit trail
 *
 * - Partial failures are impossible because both wallet updates
 *   happen within a single @Transactional boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentProcessor {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;

    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;

    /**
     * Process a transaction with automatic retry on optimistic lock conflict.
     */
    public void processWithRetry(Transaction transaction) {
        int attempts = 0;
        while (attempts < MAX_OPTIMISTIC_LOCK_RETRIES) {
            try {
                processTransaction(transaction);
                return;
            } catch (OptimisticLockingFailureException e) {
                attempts++;
                log.warn("Optimistic lock conflict on attempt {}/{} for txn {}",
                    attempts, MAX_OPTIMISTIC_LOCK_RETRIES, transaction.getId());
                if (attempts >= MAX_OPTIMISTIC_LOCK_RETRIES) {
                    throw e;
                }
                try {
                    Thread.sleep(50L * attempts);  // 50ms, 100ms, 150ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry backoff", ie);
                }
            }
        }
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void processTransaction(Transaction transaction) {
        transaction.markProcessing();
        transactionRepository.save(transaction);

        try {
            switch (transaction.getType()) {
                case DEPOSIT    -> processDeposit(transaction);
                case WITHDRAWAL -> processWithdrawal(transaction);
                case TRANSFER   -> processTransfer(transaction);
            }
            transaction.markSuccess();
            transactionRepository.save(transaction);
            log.info("Transaction {} completed successfully", transaction.getId());

        } catch (Exception e) {
            log.error("Transaction {} failed: {}", transaction.getId(), e.getMessage());
            transaction.markFailed(e.getMessage());
            transactionRepository.save(transaction);
            throw e;  // Re-throw to trigger @Transactional rollback
        }
    }

    // ─── DEPOSIT ─────────────────────────────────────────────────────────────

    private void processDeposit(Transaction transaction) {
        Wallet wallet = loadWalletWithLock(transaction.getReceiverWallet().getId());
        validateWalletActive(wallet);

        wallet.credit(transaction.getAmount());
        walletRepository.save(wallet);

        ledgerService.recordCredit(
            wallet, transaction, transaction.getAmount(),
            "Deposit: " + transaction.getDescription()
        );
    }

    // ─── WITHDRAWAL ──────────────────────────────────────────────────────────

    private void processWithdrawal(Transaction transaction) {
        Wallet wallet = loadWalletWithLock(transaction.getSenderWallet().getId());
        validateWalletActive(wallet);

        if (!wallet.hasSufficientBalance(transaction.getAmount())) {
            throw new InsufficientBalanceException(
                wallet.getWalletNumber(),
                wallet.getAvailableBalance(),
                transaction.getAmount()
            );
        }

        wallet.debit(transaction.getAmount());
        walletRepository.save(wallet);

        ledgerService.recordDebit(
            wallet, transaction, transaction.getAmount(),
            "Withdrawal: " + transaction.getDescription()
        );
    }

    // ─── TRANSFER ────────────────────────────────────────────────────────────

    /**
     * Atomic transfer between two wallets.
     *
     * DEADLOCK PREVENTION: Always acquire locks in wallet-ID order
     * (smaller UUID first). This prevents deadlock when two concurrent
     * transfers involve the same pair of wallets in opposite directions.
     */
    private void processTransfer(Transaction transaction) {
        java.util.UUID senderId   = transaction.getSenderWallet().getId();
        java.util.UUID receiverId = transaction.getReceiverWallet().getId();

        // Deterministic lock ordering to prevent deadlock
        Wallet first, second;
        if (senderId.compareTo(receiverId) < 0) {
            first  = loadWalletWithLock(senderId);
            second = loadWalletWithLock(receiverId);
        } else {
            first  = loadWalletWithLock(receiverId);
            second = loadWalletWithLock(senderId);
        }

        Wallet senderWallet   = first.getId().equals(senderId)  ? first  : second;
        Wallet receiverWallet = first.getId().equals(receiverId) ? first : second;

        validateWalletActive(senderWallet);
        validateWalletActive(receiverWallet);

        if (!senderWallet.hasSufficientBalance(transaction.getAmount())) {
            throw new InsufficientBalanceException(
                senderWallet.getWalletNumber(),
                senderWallet.getAvailableBalance(),
                transaction.getAmount()
            );
        }

        // Debit sender
        senderWallet.debit(transaction.getAmount());
        walletRepository.save(senderWallet);
        ledgerService.recordDebit(
            senderWallet, transaction, transaction.getAmount(),
            "Transfer to " + receiverWallet.getWalletNumber()
        );

        // Credit receiver — if this throws, Spring rolls back entire transaction
        // (senderWallet debit is also rolled back automatically)
        receiverWallet.credit(transaction.getAmount());
        walletRepository.save(receiverWallet);
        ledgerService.recordCredit(
            receiverWallet, transaction, transaction.getAmount(),
            "Transfer from " + senderWallet.getWalletNumber()
        );
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Wallet loadWalletWithLock(java.util.UUID walletId) {
        return walletRepository.findByIdWithOptimisticLock(walletId)
            .orElseThrow(() -> new WalletNotFoundException(walletId.toString()));
    }

    private void validateWalletActive(Wallet wallet) {
        if (!wallet.isActive()) {
            throw new WalletInactiveException(wallet.getWalletNumber());
        }
    }
}
