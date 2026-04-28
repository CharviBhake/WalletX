package com.fintech.payment.repository;

import com.fintech.payment.entity.Wallet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByUserId(UUID userId);

    Optional<Wallet> findByWalletNumber(String walletNumber);

    /**
     * Pessimistic Write Lock — use for high-contention scenarios.
     * Acquires a SELECT FOR UPDATE lock, blocking concurrent reads.
     * Use this instead of optimistic locking when:
     * - Merchant wallets with hundreds of concurrent hits
     * - You want to fail fast rather than retry
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithPessimisticLock(@Param("id") UUID id);

    /**
     * Optimistic lock version — default for regular users.
     * No DB lock held, version checked on UPDATE.
     */
    @Lock(LockModeType.OPTIMISTIC)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdWithOptimisticLock(@Param("id") UUID id);
}
