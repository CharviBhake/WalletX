package com.fintech.payment.service;

import com.fintech.payment.dto.LedgerEntryResponse;
import com.fintech.payment.entity.LedgerEntry;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.enums.LedgerEntryType;
import com.fintech.payment.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Ledger Service — implements double-entry bookkeeping.
 *
 * ACCOUNTING RULES:
 * - Every financial event creates TWO entries (debit + credit)
 * - Entries are NEVER modified or deleted
 * - Corrections use compensating entries
 *
 * Entry types:
 *   DEBIT  → money leaving a wallet  (reduces balance)
 *   CREDIT → money entering a wallet  (increases balance)
 *
 * Example: ₹500 transfer from Alice → Bob
 *   LedgerEntry(alice.wallet, DEBIT,  500, runningBalance=alice.balance-500)
 *   LedgerEntry(bob.wallet,   CREDIT, 500, runningBalance=bob.balance+500)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    /**
     * Record a DEBIT entry for the given wallet.
     * Called AFTER the wallet balance has been debited.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry recordDebit(Wallet wallet, Transaction transaction,
                                   BigDecimal amount, String description) {
        LedgerEntry entry = LedgerEntry.builder()
            .wallet(wallet)
            .transaction(transaction)
            .entryType(LedgerEntryType.DEBIT)
            .amount(amount)
            .currency(wallet.getCurrency())
            .runningBalance(wallet.getBalance())  // Balance already updated on wallet
            .description(description)
            .referenceId(transaction.getReferenceId())
            .build();

        LedgerEntry saved = ledgerEntryRepository.save(entry);
        log.debug("Ledger DEBIT: wallet={}, amount={}, runningBalance={}",
            wallet.getWalletNumber(), amount, wallet.getBalance());
        return saved;
    }

    /**
     * Record a CREDIT entry for the given wallet.
     * Called AFTER the wallet balance has been credited.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry recordCredit(Wallet wallet, Transaction transaction,
                                    BigDecimal amount, String description) {
        LedgerEntry entry = LedgerEntry.builder()
            .wallet(wallet)
            .transaction(transaction)
            .entryType(LedgerEntryType.CREDIT)
            .amount(amount)
            .currency(wallet.getCurrency())
            .runningBalance(wallet.getBalance())  // Balance already updated on wallet
            .description(description)
            .referenceId(transaction.getReferenceId())
            .build();

        LedgerEntry saved = ledgerEntryRepository.save(entry);
        log.debug("Ledger CREDIT: wallet={}, amount={}, runningBalance={}",
            wallet.getWalletNumber(), amount, wallet.getBalance());
        return saved;
    }

    /**
     * Record a compensating (rollback) entry.
     * Used when a transfer partially completed and needs reversal.
     * Compensation is the OPPOSITE of original entry type.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCompensation(Wallet wallet, Transaction transaction,
                                   BigDecimal amount, LedgerEntryType originalType) {
        LedgerEntryType compensatingType = originalType == LedgerEntryType.DEBIT
            ? LedgerEntryType.CREDIT
            : LedgerEntryType.DEBIT;

        LedgerEntry entry = LedgerEntry.builder()
            .wallet(wallet)
            .transaction(transaction)
            .entryType(compensatingType)
            .amount(amount)
            .currency(wallet.getCurrency())
            .runningBalance(wallet.getBalance())
            .description("COMPENSATION for failed transaction: " + transaction.getId())
            .referenceId(transaction.getReferenceId())
            .build();

        ledgerEntryRepository.save(entry);
        log.warn("Ledger COMPENSATION recorded: wallet={}, type={}, amount={}",
            wallet.getWalletNumber(), compensatingType, amount);
    }

    /**
     * Get paginated ledger entries for a wallet.
     */
    @Transactional(readOnly = true)
    public Page<LedgerEntryResponse> getWalletLedger(UUID walletId, Pageable pageable) {
        return ledgerEntryRepository
            .findByWalletIdOrderByCreatedAtDesc(walletId, pageable)
            .map(this::toResponse);
    }

    /**
     * Get sum of credits in a period (for bank statement generation).
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalCredits(UUID walletId, LocalDateTime from, LocalDateTime to) {
        return ledgerEntryRepository.sumByWalletAndTypeAndPeriod(
            walletId, LedgerEntryType.CREDIT, from, to);
    }

    /**
     * Get sum of debits in a period.
     */
    @Transactional(readOnly = true)
    public BigDecimal getTotalDebits(UUID walletId, LocalDateTime from, LocalDateTime to) {
        return ledgerEntryRepository.sumByWalletAndTypeAndPeriod(
            walletId, LedgerEntryType.DEBIT, from, to);
    }

    private LedgerEntryResponse toResponse(LedgerEntry entry) {
        return LedgerEntryResponse.builder()
            .id(entry.getId())
            .transactionId(entry.getTransaction().getId())
            .entryType(entry.getEntryType())
            .amount(entry.getAmount())
            .runningBalance(entry.getRunningBalance())
            .description(entry.getDescription())
            .createdAt(entry.getCreatedAt())
            .build();
    }
}
