package com.fintech.payment.controller;

import com.fintech.payment.dto.LedgerEntryResponse;
import com.fintech.payment.entity.User;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.exception.UserNotFoundException;
import com.fintech.payment.exception.WalletNotFoundException;
import com.fintech.payment.repository.UserRepository;
import com.fintech.payment.repository.WalletRepository;
import com.fintech.payment.service.LedgerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
@Tag(name = "Ledger", description = "Immutable financial ledger — double-entry bookkeeping")
@SecurityRequirement(name = "bearerAuth")
public class LedgerController {

    private final LedgerService    ledgerService;
    private final UserRepository   userRepository;
    private final WalletRepository walletRepository;

    @GetMapping("/entries")
    @Operation(summary = "Get paginated ledger entries for current wallet",
               description = "Each entry shows the running balance at that point in time.")
    public ResponseEntity<Page<LedgerEntryResponse>> getLedger(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        Wallet wallet = getWallet(userDetails.getUsername());
        PageRequest pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(ledgerService.getWalletLedger(wallet.getId(), pageable));
    }

    @GetMapping("/statement")
    @Operation(summary = "Get credit/debit summary for a time period")
    public ResponseEntity<Map<String, Object>> getStatement(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {

        Wallet wallet = getWallet(userDetails.getUsername());

        BigDecimal totalCredits = ledgerService.getTotalCredits(wallet.getId(), from, to);
        BigDecimal totalDebits  = ledgerService.getTotalDebits(wallet.getId(), from, to);
        BigDecimal net          = totalCredits.subtract(totalDebits);

        return ResponseEntity.ok(Map.of(
            "walletNumber",  wallet.getWalletNumber(),
            "currency",      wallet.getCurrency(),
            "periodFrom",    from,
            "periodTo",      to,
            "totalCredits",  totalCredits,
            "totalDebits",   totalDebits,
            "netChange",     net,
            "closingBalance", wallet.getBalance()
        ));
    }

    private Wallet getWallet(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException(username));
        return walletRepository.findByUserId(user.getId())
            .orElseThrow(() -> new WalletNotFoundException("user:" + username));
    }
}
