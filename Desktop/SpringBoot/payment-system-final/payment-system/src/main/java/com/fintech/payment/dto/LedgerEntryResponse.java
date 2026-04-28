package com.fintech.payment.dto;

import com.fintech.payment.enums.LedgerEntryType;
import com.fintech.payment.enums.TransactionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class LedgerEntryResponse {
    private UUID id;
    private UUID transactionId;
    private LedgerEntryType entryType;
    private BigDecimal amount;
    private BigDecimal runningBalance;
    private String description;
    private LocalDateTime createdAt;
}
