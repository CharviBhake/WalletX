package com.fintech.payment.exception;
import org.springframework.http.HttpStatus;
public class UserNotFoundException extends PaymentException {
    public UserNotFoundException(String identifier) {
        super("User not found: " + identifier, HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }
}
