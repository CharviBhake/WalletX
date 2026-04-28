package com.fintech.payment.controller;

import com.fintech.payment.dto.*;
import com.fintech.payment.service.impl.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Deposit, Withdraw, Transfer")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Deposit money into the authenticated user's wallet.
     *
     * IDEMPOTENCY:
     * Clients MUST send a unique Idempotency-Key header per request.
     * Retrying with the same key returns the original response without re-processing.
     * Use UUID v4: e.g. "550e8400-e29b-41d4-a716-446655440000"
     */
    @PostMapping("/deposit")
    @Operation(summary = "Add money to wallet",
               description = "Idempotent. Send unique Idempotency-Key header per operation.")
    public ResponseEntity<TransactionResponse> deposit(
            @AuthenticationPrincipal UserDetails userDetails,
            @Parameter(description = "Unique key to prevent duplicate processing", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositRequest request) {

        String requestBody = serializeRequest(request);
        TransactionResponse response = paymentService.deposit(
            userDetails.getUsername(), idempotencyKey, requestBody, request);

        // 200 for duplicate, 202 for new (accepted for async processing)
        HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/withdraw")
    @Operation(summary = "Withdraw money from wallet")
    public ResponseEntity<TransactionResponse> withdraw(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawRequest request) {

        String requestBody = serializeRequest(request);
        TransactionResponse response = paymentService.withdraw(
            userDetails.getUsername(), idempotencyKey, requestBody, request);

        HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/transfer")
    @Operation(summary = "Transfer money to another wallet")
    public ResponseEntity<TransactionResponse> transfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {

        String requestBody = serializeRequest(request);
        TransactionResponse response = paymentService.transfer(
            userDetails.getUsername(), idempotencyKey, requestBody, request);

        HttpStatus status = response.isDuplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(response);
    }

    @GetMapping("/history")
    @Operation(summary = "Get paginated transaction history")
    public ResponseEntity<Page<TransactionResponse>> getHistory(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(paymentService.getTransactionHistory(
            userDetails.getUsername(), pageable));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Get a specific transaction by ID")
    public ResponseEntity<TransactionResponse> getTransaction(
            @AuthenticationPrincipal UserDetails userDetails,
            @PathVariable UUID transactionId) {

        return ResponseEntity.ok(paymentService.getTransaction(
            userDetails.getUsername(), transactionId));
    }

    // Simple request serialization for hashing (avoid Jackson dependency in controller)
    private String serializeRequest(Object request) {
        return request.toString();
    }
}
