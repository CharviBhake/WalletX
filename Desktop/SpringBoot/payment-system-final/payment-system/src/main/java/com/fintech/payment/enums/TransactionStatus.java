package com.fintech.payment.enums;

public enum TransactionStatus {
    PENDING,        // Initial state — payment queued
    PROCESSING,     // Being processed by queue consumer
    SUCCESS,        // Completed successfully
    FAILED,         // Failed after retries
    ROLLED_BACK,    // Compensated after partial failure
    DEAD_LETTERED   // Moved to DLQ after max retries
}
