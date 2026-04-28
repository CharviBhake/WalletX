package com.fintech.payment.exception;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
public class DailyLimitExceededException extends PaymentException {
    public DailyLimitExceededException(BigDecimal limit) {
        super(String.format("Daily transaction limit of %.2f exceeded", limit),
            HttpStatus.UNPROCESSABLE_ENTITY, "DAILY_LIMIT_EXCEEDED");
    }
}
