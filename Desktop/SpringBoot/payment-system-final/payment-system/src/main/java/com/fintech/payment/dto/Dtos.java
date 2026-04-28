package com.fintech.payment.dto;

import com.fintech.payment.enums.LedgerEntryType;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import jakarta.validation.constraints.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

// ═══════════════════════════════════════════════════════════
// AUTH
// ═══════════════════════════════════════════════════════════

// ═══════════════════════════════════════════════════════════
// WALLET
// ═══════════════════════════════════════════════════════════

class WalletDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class WalletResponse {
        private UUID id;
        private String walletNumber;
        private BigDecimal balance;
        private BigDecimal availableBalance;
        private BigDecimal reservedBalance;
        private String currency;
        private boolean active;
        private LocalDateTime createdAt;
    }
}

// ═══════════════════════════════════════════════════════════
// PAYMENT REQUESTS
// ═══════════════════════════════════════════════════════════

class PaymentDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DepositRequest {
        @NotNull
        @DecimalMin(value = "0.01", message = "Minimum deposit is 0.01")
        @DecimalMax(value = "100000.00", message = "Maximum deposit is 100,000")
        private BigDecimal amount;

        @Size(max = 500)
        private String description;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class WithdrawRequest {
        @NotNull
        @DecimalMin(value = "0.01")
        @DecimalMax(value = "100000.00")
        private BigDecimal amount;

        @Size(max = 500)
        private String description;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TransferRequest {
        @NotBlank
        private String receiverWalletNumber;

        @NotNull
        @DecimalMin(value = "0.01")
        @DecimalMax(value = "100000.00")
        private BigDecimal amount;

        @Size(max = 500)
        private String description;

        @Size(max = 100)
        private String referenceId;
    }
}

// ═══════════════════════════════════════════════════════════
// TRANSACTION RESPONSE
// ═══════════════════════════════════════════════════════════

class TransactionDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TransactionResponse {
        private UUID id;
        private String idempotencyKey;
        private TransactionType type;
        private TransactionStatus status;
        private BigDecimal amount;
        private String currency;
        private String description;
        private String referenceId;
        private String senderWalletNumber;
        private String receiverWalletNumber;
        private int retryCount;
        private String failureReason;
        private LocalDateTime createdAt;
        private LocalDateTime processedAt;
        private LocalDateTime completedAt;
        private boolean isDuplicate;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TransactionPageResponse {
        private java.util.List<TransactionResponse> transactions;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }
}

// ═══════════════════════════════════════════════════════════
// LEDGER
// ═══════════════════════════════════════════════════════════

class LedgerDto {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LedgerEntryResponse {
        private UUID id;
        private UUID transactionId;
        private LedgerEntryType entryType;
        private BigDecimal amount;
        private BigDecimal runningBalance;
        private String description;
        private LocalDateTime createdAt;
    }
}

// ═══════════════════════════════════════════════════════════
// QUEUE MESSAGE
// ═══════════════════════════════════════════════════════════

class PaymentMessage {

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PaymentQueueMessage {
        private UUID transactionId;
        private TransactionType type;
        private UUID senderWalletId;
        private UUID receiverWalletId;
        private BigDecimal amount;
        private String currency;
        private int retryCount;
        private LocalDateTime enqueuedAt;
    }
}
