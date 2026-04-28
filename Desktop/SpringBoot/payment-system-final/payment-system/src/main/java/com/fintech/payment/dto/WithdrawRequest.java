package com.fintech.payment.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WithdrawRequest {
    @NotNull
    @DecimalMin(value = "0.01")
    @DecimalMax(value = "100000.00")
    private BigDecimal amount;

    @Size(max = 500)
    private String description;
}
