package com.fintech.payment.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DepositRequest {
    @NotNull
    @DecimalMin(value = "0.01", message = "Minimum deposit is 0.01")
    @DecimalMax(value = "100000.00", message = "Maximum deposit is 100,000")
    private BigDecimal amount;

    @Size(max = 500)
    private String description;
}
