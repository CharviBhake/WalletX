package com.fintech.payment.repository;

import com.fintech.payment.entity.Transaction;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
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
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    // All transactions for a wallet (sent + received), sorted newest first
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.senderWallet.id = :walletId
           OR t.receiverWallet.id = :walletId
        ORDER BY t.createdAt DESC
        """)
    Page<Transaction> findAllByWalletId(@Param("walletId") UUID walletId, Pageable pageable);

    // Transactions by status (for admin/monitoring)
    List<Transaction> findByStatusOrderByCreatedAtAsc(TransactionStatus status);

    // Transactions pending retry
    @Query("""
        SELECT t FROM Transaction t
        WHERE t.status = 'FAILED'
          AND t.retryCount < :maxRetries
          AND t.updatedAt < :retryBefore
        ORDER BY t.updatedAt ASC
        """)
    List<Transaction> findRetryableTransactions(
        @Param("maxRetries") int maxRetries,
        @Param("retryBefore") LocalDateTime retryBefore
    );

    // Daily volume check (rate limiting)
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM Transaction t
        WHERE t.senderWallet.id = :walletId
          AND t.type = :type
          AND t.status = 'SUCCESS'
          AND t.createdAt >= :since
        """)
    BigDecimal sumByWalletAndTypeAndStatusSince(
        @Param("walletId") UUID walletId,
        @Param("type") TransactionType type,
        @Param("since") LocalDateTime since
    );
}
