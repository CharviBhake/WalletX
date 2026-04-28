package com.fintech.payment.exception;

import org.springframework.http.HttpStatus;

public class WalletInactiveException extends PaymentException {
    public WalletInactiveException(String walletNumber) {
        super("Wallet is inactive: " + walletNumber, HttpStatus.FORBIDDEN, "WALLET_INACTIVE");
    }
}