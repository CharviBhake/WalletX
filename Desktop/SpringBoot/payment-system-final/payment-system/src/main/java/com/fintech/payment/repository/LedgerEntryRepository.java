package com.fintech.payment.repository;

import com.fintech.payment.entity.LedgerEntry;
import com.fintech.payment.enums.LedgerEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    Page<LedgerEntry> findByWalletIdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    List<LedgerEntry> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);

    // Last ledger entry for a wallet — get current running balance from ledger
    @Query("""
        SELECT l FROM LedgerEntry l
        WHERE l.wallet.id = :walletId
        ORDER BY l.createdAt DESC
        LIMIT 1
        """)
    Optional<LedgerEntry> findLatestByWalletId(@Param("walletId") UUID walletId);

    // Sum of credits/debits in a time window — for statements
    @Query("""
        SELECT COALESCE(SUM(l.amount), 0) FROM LedgerEntry l
        WHERE l.wallet.id = :walletId
          AND l.entryType = :entryType
          AND l.createdAt BETWEEN :from AND :to
        """)
    BigDecimal sumByWalletAndTypeAndPeriod(
        @Param("walletId") UUID walletId,
        @Param("entryType") LedgerEntryType entryType,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to
    );
}
