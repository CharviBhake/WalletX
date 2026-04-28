package com.fintech.payment;

import com.fintech.payment.dto.WalletResponse;
import com.fintech.payment.entity.User;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.exception.UserNotFoundException;
import com.fintech.payment.exception.WalletNotFoundException;
import com.fintech.payment.repository.UserRepository;
import com.fintech.payment.repository.WalletRepository;
import com.fintech.payment.service.impl.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WalletService Tests")
class WalletServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private UserRepository   userRepository;

    @InjectMocks private WalletService walletService;

    @Test
    @DisplayName("getWalletByUsername returns correct wallet response")
    void getWalletByUsername_success() {
        UUID userId = UUID.randomUUID();
        User user = User.builder().id(userId).username("alice").build();
        Wallet wallet = Wallet.builder()
            .id(UUID.randomUUID())
            .user(user)
            .walletNumber("WL-TEST001")
            .balance(new BigDecimal("1500.00"))
            .reservedBalance(new BigDecimal("200.00"))
            .currency("INR")
            .active(true)
            .build();

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.of(wallet));

        WalletResponse response = walletService.getWalletByUsername("alice");

        assertThat(response.getWalletNumber()).isEqualTo("WL-TEST001");
        assertThat(response.getBalance()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(response.getAvailableBalance()).isEqualByComparingTo(new BigDecimal("1300.00"));
        assertThat(response.getReservedBalance()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("getWalletByUsername throws UserNotFoundException for unknown user")
    void getWalletByUsername_unknownUser_throwsException() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> walletService.getWalletByUsername("ghost"))
            .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    @DisplayName("getWalletByNumber throws WalletNotFoundException for unknown wallet number")
    void getWalletByNumber_unknown_throwsException() {
        when(walletRepository.findByWalletNumber("WL-FAKE001")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> walletService.getWalletByNumber("WL-FAKE001"))
            .isInstanceOf(WalletNotFoundException.class);
    }
}
