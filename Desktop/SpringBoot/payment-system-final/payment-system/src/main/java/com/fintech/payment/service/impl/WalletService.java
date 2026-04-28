package com.fintech.payment.service.impl;

import com.fintech.payment.dto.WalletResponse;
import com.fintech.payment.entity.User;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.exception.UserNotFoundException;
import com.fintech.payment.exception.WalletNotFoundException;
import com.fintech.payment.repository.UserRepository;
import com.fintech.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public WalletResponse getWalletByUsername(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new UserNotFoundException(username));

        Wallet wallet = walletRepository.findByUserId(user.getId())
            .orElseThrow(() -> new WalletNotFoundException("user:" + username));

        return toResponse(wallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletById(UUID walletId) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new WalletNotFoundException(walletId.toString()));
        return toResponse(wallet);
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletByNumber(String walletNumber) {
        Wallet wallet = walletRepository.findByWalletNumber(walletNumber)
            .orElseThrow(() -> new WalletNotFoundException(walletNumber));
        return toResponse(wallet);
    }

    private WalletResponse toResponse(Wallet w) {
        return WalletResponse.builder()
            .id(w.getId())
            .walletNumber(w.getWalletNumber())
            .balance(w.getBalance())
            .availableBalance(w.getAvailableBalance())
            .reservedBalance(w.getReservedBalance())
            .currency(w.getCurrency())
            .active(w.isActive())
            .createdAt(w.getCreatedAt())
            .build();
    }
}
