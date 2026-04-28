package com.fintech.payment.dto;

import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TransactionResponse {
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

    /**
     * True if this response was returned from idempotency cache
     * (i.e., the client sent a duplicate request).
     */
    private boolean duplicate;
}
