package com.fintech.payment.exception;
import org.springframework.http.HttpStatus;
import java.util.UUID;
public class TransactionNotFoundException extends PaymentException {
    public TransactionNotFoundException(UUID id) {
        super("Transaction not found: " + id, HttpStatus.NOT_FOUND, "TRANSACTION_NOT_FOUND");
    }
}
