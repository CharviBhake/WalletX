package com.fintech.payment.service.impl;

import com.fintech.payment.dto.*;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.entity.User;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import com.fintech.payment.exception.*;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.repository.UserRepository;
import com.fintech.payment.repository.WalletRepository;
import com.fintech.payment.service.IdempotencyService;
import com.fintech.payment.service.PaymentQueueProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Payment Service — public-facing orchestration layer.
 *
 * Responsibilities:
 * 1. Idempotency check (before any DB write)
 * 2. Input validation (balance, limits, wallet state)
 * 3. Transaction record creation (PENDING state)
 * 4. Async queue dispatch (fire-and-forget to RabbitMQ)
 * 5. Transaction history queries
 *
 * Actual money movement happens in PaymentProcessor (queue consumer).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final UserRepository          userRepository;
    private final WalletRepository        walletRepository;
    private final TransactionRepository   transactionRepository;
    private final IdempotencyService      idempotencyService;
    private final PaymentQueueProducer    queueProducer;

    @Value("${app.payment.daily-limit:500000.00}")
    private BigDecimal dailyLimit;

    // ─── DEPOSIT ─────────────────────────────────────────────────────────────

    @Transactional
    public TransactionResponse deposit(String username, String idempotencyKey,
                                       String requestBody, DepositRequest request) {
        // 1. Idempotency check
        String requestHash = idempotencyService.computeHash(requestBody);
        var existing = idempotencyService.checkIdempotency(idempotencyKey, requestHash);
        if (existing.isPresent()) {
            log.info("Duplicate deposit request detected, key={}", idempotencyKey);
            return existing.get();
        }

        // 2. Load wallet
        Wallet wallet = getWalletByUsername(username);
        validateWalletActive(wallet);

        // 3. Create PENDING transaction
        Transaction txn = Transaction.builder()
            .idempotencyKey(idempotencyKey)
            .type(TransactionType.DEPOSIT)
            .status(TransactionStatus.PENDING)
            .receiverWallet(wallet)
            .amount(request.getAmount())
            .currency(wallet.getCurrency())
            .description(request.getDescription())
            .build();
        txn = transactionRepository.save(txn);

        // 4. Record idempotency
        idempotencyService.recordIdempotency(idempotencyKey, requestHash, txn);

        // 5. Enqueue for async processing
        queueProducer.enqueuePayment(txn);

        log.info("Deposit queued: txnId={}, wallet={}, amount={}",
            txn.getId(), wallet.getWalletNumber(), request.getAmount());

        return toResponse(txn, false);
    }

    // ─── WITHDRAWAL ──────────────────────────────────────────────────────────

    @Transactional
    public TransactionResponse withdraw(String username, String idempotencyKey,
                                        String requestBody, WithdrawRequest request) {
        String requestHash = idempotencyService.computeHash(requestBody);
        var existing = idempotencyService.checkIdempotency(idempotencyKey, requestHash);
        if (existing.isPresent()) return existing.get();

        Wallet wallet = getWalletByUsername(username);
        validateWalletActive(wallet);

        // Eagerly validate balance before queuing
        if (!wallet.hasSufficientBalance(request.getAmount())) {
            throw new InsufficientBalanceException(
                wallet.getWalletNumber(), wallet.getAvailableBalance(), request.getAmount());
        }

        // Daily limit check
        checkDailyLimit(wallet.getId(), TransactionType.WITHDRAWAL, request.getAmount());

        Transaction txn = Transaction.builder()
            .idempotencyKey(idempotencyKey)
            .type(TransactionType.WITHDRAWAL)
            .status(TransactionStatus.PENDING)
            .senderWallet(wallet)
            .amount(request.getAmount())
            .currency(wallet.getCurrency())
            .description(request.getDescription())
            .build();
        txn = transactionRepository.save(txn);

        idempotencyService.recordIdempotency(idempotencyKey, requestHash, txn);
        queueProducer.enqueuePayment(txn);

        log.info("Withdrawal queued: txnId={}, wallet={}, amount={}",
            txn.getId(), wallet.getWalletNumber(), request.getAmount());

        return toResponse(txn, false);
    }

    // ─── TRANSFER ────────────────────────────────────────────────────────────

    @Transactional
    public TransactionResponse transfer(String username, String idempotencyKey,
                                        String requestBody, TransferRequest request) {
        String requestHash = idempotencyService.computeHash(requestBody);
        var existing = idempotencyService.checkIdempotency(idempotencyKey, requestHash);
        if (existing.isPresent()) return existing.get();

        Wallet senderWallet = getWalletByUsername(username);
        Wallet receiverWallet = walletRepository.findByWalletNumber(request.getReceiverWalletNumber())
            .orElseThrow(() -> new WalletNotFoundException(request.getReceiverWalletNumber()));

        // Guard: no self-transfers
        if (senderWallet.getId().equals(receiverWallet.getId())) {
            throw new SelfTransferException();
        }

        validateWalletActive(senderWallet);
        validateWalletActive(receiverWallet);

        if (!senderWallet.hasSufficientBalance(request.getAmount())) {
            throw new InsufficientBalanceException(
                senderWallet.getWalletNumber(),
                senderWallet.getAvailableBalance(),
                request.getAmount());
        }

        checkDailyLimit(senderWallet.getId(), TransactionType.TRANSFER, request.getAmount());

        Transaction txn = Transaction.builder()
            .idempotencyKey(idempotencyKey)
            .type(TransactionType.TRANSFER)
            .status(TransactionStatus.PENDING)
            .senderWallet(senderWallet)
            .receiverWallet(receiverWallet)
            .amount(request.getAmount())
            .currency(senderWallet.getCurrency())
            .description(request.getDescription())
            .referenceId(request.getReferenceId())
            .build();
        txn = transactionRepository.save(txn);

        idempotencyService.recordIdempotency(idempotencyKey, requestHash, txn);
        queueProducer.enqueuePayment(txn);

        log.info("Transfer queued: txnId={}, from={}, to={}, amount={}",
            txn.getId(), senderWallet.getWalletNumber(),
            receiverWallet.getWalletNumber(), request.getAmount());

        return toResponse(txn, false);
    }

    // ─── QUERIES ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<TransactionResponse> getTransactionHistory(String username, Pageable pageable) {
        Wallet wallet = getWalletByUsername(username);
        return transactionRepository
            .findAllByWalletId(wallet.getId(), pageable)
            .map(t -> toResponse(t, false));
    }

    @Transactional(readOnly = true)
    public TransactionResponse getTransaction(String username, UUID transactionId) {
        Transaction txn = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        // Ensure the transaction belongs to the requesting user
        Wallet wallet = getWalletByUsername(username);
        boolean isSender   = txn.getSenderWallet()   != null && txn.getSenderWallet().getId().equals(wallet.getId());
        boolean isReceiver = txn.getReceiverWallet() != null && txn.getReceiverWallet().getId().equals(wallet.getId());

        if (!isSender && !isReceiver) {
            throw new PaymentException("Transaction not found", org.springframework.http.HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND");
        }

        return toResponse(txn, false);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Wallet getWalletByUsername(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException(username));
        return walletRepository.findByUserId(user.getId())
            .orElseThrow(() -> new WalletNotFoundException("user:" + username));
    }

    private void validateWalletActive(Wallet wallet) {
        if (!wallet.isActive()) {
            throw new WalletInactiveException(wallet.getWalletNumber());
        }
    }

    private void checkDailyLimit(UUID walletId, TransactionType type, BigDecimal amount) {
        BigDecimal todayTotal = transactionRepository.sumByWalletAndTypeAndStatusSince(
            walletId, type, LocalDateTime.now().withHour(0).withMinute(0).withSecond(0));

        if (todayTotal.add(amount).compareTo(dailyLimit) > 0) {
            throw new DailyLimitExceededException(dailyLimit);
        }
    }

    private TransactionResponse toResponse(Transaction t, boolean isDuplicate) {
        return TransactionResponse.builder()
            .id(t.getId())
            .idempotencyKey(t.getIdempotencyKey())
            .type(t.getType())
            .status(t.getStatus())
            .amount(t.getAmount())
            .currency(t.getCurrency())
            .description(t.getDescription())
            .referenceId(t.getReferenceId())
            .senderWalletNumber(t.getSenderWallet()   != null ? t.getSenderWallet().getWalletNumber()   : null)
            .receiverWalletNumber(t.getReceiverWallet() != null ? t.getReceiverWallet().getWalletNumber() : null)
            .retryCount(t.getRetryCount())
            .failureReason(t.getFailureReason())
            .createdAt(t.getCreatedAt())
            .processedAt(t.getProcessedAt())
            .completedAt(t.getCompletedAt())
            .duplicate(isDuplicate)
            .build();
    }
}
