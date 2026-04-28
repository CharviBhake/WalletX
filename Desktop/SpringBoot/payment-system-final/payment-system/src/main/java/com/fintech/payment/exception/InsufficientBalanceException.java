package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;
import java.math.BigDecimal;

public class InsufficientBalanceException extends PaymentException {
    public InsufficientBalanceException(String walletNumber, BigDecimal available, BigDecimal required) {
        super(
                String.format("Insufficient balance in wallet %s. Available: %.2f, Required: %.2f",
                        walletNumber, available, required),
                HttpStatus.UNPROCESSABLE_ENTITY,
                "INSUFFICIENT_BALANCE"
        );
    }
}