package com.fintech.payment.service.impl;

import com.fintech.payment.config.JwtService;
import com.fintech.payment.dto.AuthDtos;
import com.fintech.payment.entity.User;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.exception.PaymentException;
import com.fintech.payment.repository.UserRepository;
import com.fintech.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsServiceImpl userDetailsService;

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new PaymentException(
                "Username already taken: " + request.getUsername(),
                HttpStatus.CONFLICT, "USERNAME_TAKEN");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new PaymentException(
                "Email already registered: " + request.getEmail(),
                HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        // Create user
        User user = User.builder()
            .username(request.getUsername())
            .email(request.getEmail())
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName())
            .build();
        user = userRepository.save(user);

        // Auto-create wallet for new user
        Wallet wallet = Wallet.builder()
            .user(user)
            .walletNumber(generateWalletNumber())
            .balance(BigDecimal.ZERO)
            .currency("INR")
            .build();
        wallet = walletRepository.save(wallet);

        log.info("Registered user: {}, wallet: {}", user.getUsername(), wallet.getWalletNumber());

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtService.generateToken(userDetails);

        return AuthDtos.AuthResponse.builder()
            .token(token)
            .tokenType("Bearer")
            .userId(user.getId())
            .username(user.getUsername())
            .walletId(wallet.getId())
            .build();
    }

    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        authenticationManager.authenticate(
            new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        User user = userRepository.findByUsername(request.getUsername())
            .orElseThrow(() -> new PaymentException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        Wallet wallet = walletRepository.findByUserId(user.getId())
            .orElseThrow(() -> new PaymentException("Wallet not found", HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND"));

        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String token = jwtService.generateToken(userDetails);

        return AuthDtos.AuthResponse.builder()
            .token(token)
            .tokenType("Bearer")
            .userId(user.getId())
            .username(user.getUsername())
            .walletId(wallet.getId())
            .build();
    }

    private String generateWalletNumber() {
        // Format: WL-XXXXXXXXXX (10 random digits)
        String digits = String.valueOf(System.currentTimeMillis()).substring(3);
        return "WL-" + digits + String.format("%04d", (int)(Math.random() * 10000));
    }
}
