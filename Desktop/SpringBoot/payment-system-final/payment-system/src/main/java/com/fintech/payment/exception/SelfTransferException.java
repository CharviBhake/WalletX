package com.fintech.payment.exception;
import org.springframework.http.HttpStatus;
public class SelfTransferException extends PaymentException {
    public SelfTransferException() {
        super("Cannot transfer to your own wallet", HttpStatus.BAD_REQUEST, "SELF_TRANSFER_NOT_ALLOWED");
    }
}
