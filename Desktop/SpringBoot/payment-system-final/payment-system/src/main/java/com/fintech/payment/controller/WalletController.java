package com.fintech.payment.controller;

import com.fintech.payment.dto.WalletResponse;
import com.fintech.payment.service.impl.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Wallet balance and info")
@SecurityRequirement(name = "bearerAuth")
public class WalletController {

    private final WalletService walletService;

    @GetMapping("/me")
    @Operation(summary = "Get current user's wallet")
    public ResponseEntity<WalletResponse> getMyWallet(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(walletService.getWalletByUsername(userDetails.getUsername()));
    }

    @GetMapping("/number/{walletNumber}")
    @Operation(summary = "Look up wallet by wallet number")
    public ResponseEntity<WalletResponse> getByNumber(@PathVariable String walletNumber) {
        return ResponseEntity.ok(walletService.getWalletByNumber(walletNumber));
    }
}
