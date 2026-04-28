package com.fintech.payment.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WalletResponse {
    private UUID id;
    private String walletNumber;
    private BigDecimal balance;
    private BigDecimal availableBalance;
    private BigDecimal reservedBalance;
    private String currency;
    private boolean active;
    private LocalDateTime createdAt;
}
