package com.fintech.payment.dto;

import com.fintech.payment.enums.TransactionType;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Message payload published to the payment processing queue.
 * Must be serializable for RabbitMQ delivery.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentQueueMessage implements Serializable {

    private UUID transactionId;
    private TransactionType type;
    private UUID senderWalletId;
    private UUID receiverWalletId;
    private BigDecimal amount;
    private String currency;

    /** Tracks how many times this message has been retried. */
    private int retryCount;

    private LocalDateTime enqueuedAt;
}
