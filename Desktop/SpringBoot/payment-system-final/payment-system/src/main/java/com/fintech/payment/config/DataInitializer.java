package com.fintech.payment.config;

import com.fintech.payment.entity.User;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.repository.UserRepository;
import com.fintech.payment.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Seeds development data on application startup.
 * Only active in the default (dev) profile — never runs in prod.
 *
 * Pre-seeded accounts:
 *  alice / password123  →  Wallet: WL-1000000001  (₹50,000 balance)
 *  bob   / password123  →  Wallet: WL-1000000002  (₹20,000 balance)
 *  carol / password123  →  Wallet: WL-1000000003  (₹5,000 balance)
 */
@Slf4j
@Component
@Profile("!prod")   // Never runs in production
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final UserRepository    userRepository;
    private final WalletRepository  walletRepository;
    private final PasswordEncoder   passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            log.info("DataInitializer: data already exists, skipping seed");
            return;
        }

        log.info("DataInitializer: seeding development data...");

        createUserWithWallet("alice",  "alice@fintech.dev",  "Alice Johnson", "WL-1000000001", new BigDecimal("50000.00"));
        createUserWithWallet("bob",    "bob@fintech.dev",    "Bob Smith",     "WL-1000000002", new BigDecimal("20000.00"));
        createUserWithWallet("carol",  "carol@fintech.dev",  "Carol Williams","WL-1000000003", new BigDecimal("5000.00"));

        log.info("DataInitializer: ✅ Seeded 3 users with wallets");
        log.info("─────────────────────────────────────────────");
        log.info("  Swagger UI  →  http://localhost:8080/swagger-ui.html");
        log.info("  H2 Console  →  http://localhost:8080/h2-console");
        log.info("  RabbitMQ    →  http://localhost:15672  (guest/guest)");
        log.info("─────────────────────────────────────────────");
        log.info("  Test credentials:");
        log.info("    alice / password123  →  WL-1000000001  (₹50,000)");
        log.info("    bob   / password123  →  WL-1000000002  (₹20,000)");
        log.info("    carol / password123  →  WL-1000000003  (₹5,000)");
        log.info("─────────────────────────────────────────────");
    }

    private void createUserWithWallet(String username, String email,
                                       String fullName, String walletNumber,
                                       BigDecimal initialBalance) {
        User user = User.builder()
            .username(username)
            .email(email)
            .passwordHash(passwordEncoder.encode("password123"))
            .fullName(fullName)
            .active(true)
            .build();
        user = userRepository.save(user);

        Wallet wallet = Wallet.builder()
            .user(user)
            .walletNumber(walletNumber)
            .balance(initialBalance)
            .currency("INR")
            .active(true)
            .build();
        walletRepository.save(wallet);
    }
}
