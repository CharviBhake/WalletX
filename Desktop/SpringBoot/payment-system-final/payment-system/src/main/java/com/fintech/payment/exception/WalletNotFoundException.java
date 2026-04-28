package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.UUID;

public class WalletNotFoundException extends PaymentException {
    public WalletNotFoundException(String identifier) {
        super("Wallet not found: " + identifier, HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND");
    }
}
