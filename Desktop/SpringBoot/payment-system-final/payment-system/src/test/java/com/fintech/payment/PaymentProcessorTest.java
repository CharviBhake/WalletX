package com.fintech.payment;

import com.fintech.payment.entity.Transaction;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import com.fintech.payment.exception.InsufficientBalanceException;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.repository.WalletRepository;
import com.fintech.payment.service.LedgerService;
import com.fintech.payment.service.PaymentProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentProcessor Tests")
class PaymentProcessorTest {

    @Mock private WalletRepository     walletRepository;
    @Mock private TransactionRepository transactionRepository;
    @Mock private LedgerService         ledgerService;

    @InjectMocks private PaymentProcessor paymentProcessor;

    private Wallet senderWallet;
    private Wallet receiverWallet;
    private Transaction transferTxn;

    @BeforeEach
    void setUp() {
        senderWallet = Wallet.builder()
            .id(UUID.randomUUID())
            .walletNumber("WL-SENDER001")
            .balance(new BigDecimal("1000.00"))
            .reservedBalance(BigDecimal.ZERO)
            .currency("INR")
            .active(true)
            .version(1L)
            .build();

        receiverWallet = Wallet.builder()
            .id(UUID.randomUUID())
            .walletNumber("WL-RECV001")
            .balance(new BigDecimal("500.00"))
            .reservedBalance(BigDecimal.ZERO)
            .currency("INR")
            .active(true)
            .version(1L)
            .build();

        transferTxn = Transaction.builder()
            .id(UUID.randomUUID())
            .idempotencyKey(UUID.randomUUID().toString())
            .type(TransactionType.TRANSFER)
            .status(TransactionStatus.PENDING)
            .senderWallet(senderWallet)
            .receiverWallet(receiverWallet)
            .amount(new BigDecimal("200.00"))
            .currency("INR")
            .retryCount(0)
            .build();
    }

    @Nested
    @DisplayName("Transfer Tests")
    class TransferTests {

        @Test
        @DisplayName("Successful transfer debits sender and credits receiver atomically")
        void transfer_success_updatesBalancesCorrectly() {
            when(walletRepository.findByIdWithOptimisticLock(senderWallet.getId()))
                .thenReturn(Optional.of(senderWallet));
            when(walletRepository.findByIdWithOptimisticLock(receiverWallet.getId()))
                .thenReturn(Optional.of(receiverWallet));
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentProcessor.processTransaction(transferTxn);

            // Sender balance reduced by 200
            assertThat(senderWallet.getBalance())
                .isEqualByComparingTo(new BigDecimal("800.00"));

            // Receiver balance increased by 200
            assertThat(receiverWallet.getBalance())
                .isEqualByComparingTo(new BigDecimal("700.00"));

            // Transaction marked SUCCESS
            assertThat(transferTxn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);

            // Ledger entries recorded — one debit, one credit
            verify(ledgerService).recordDebit(eq(senderWallet), eq(transferTxn),
                eq(new BigDecimal("200.00")), anyString());
            verify(ledgerService).recordCredit(eq(receiverWallet), eq(transferTxn),
                eq(new BigDecimal("200.00")), anyString());
        }

        @Test
        @DisplayName("Transfer fails with InsufficientBalanceException when sender has low balance")
        void transfer_insufficientBalance_throwsException() {
            transferTxn = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .type(TransactionType.TRANSFER)
                .status(TransactionStatus.PENDING)
                .senderWallet(senderWallet)
                .receiverWallet(receiverWallet)
                .amount(new BigDecimal("5000.00"))  // More than balance of 1000
                .currency("INR")
                .retryCount(0)
                .build();

            when(walletRepository.findByIdWithOptimisticLock(any()))
                .thenReturn(Optional.of(senderWallet))
                .thenReturn(Optional.of(receiverWallet));
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> paymentProcessor.processTransaction(transferTxn))
                .isInstanceOf(InsufficientBalanceException.class);

            // Balances must not change
            assertThat(senderWallet.getBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));
            assertThat(receiverWallet.getBalance()).isEqualByComparingTo(new BigDecimal("500.00"));

            // Transaction must be marked FAILED
            assertThat(transferTxn.getStatus()).isEqualTo(TransactionStatus.FAILED);

            // No ledger entries recorded
            verifyNoInteractions(ledgerService);
        }
    }

    @Nested
    @DisplayName("Deposit Tests")
    class DepositTests {

        @Test
        @DisplayName("Deposit credits wallet and records ledger entry")
        void deposit_success_creditsWallet() {
            Transaction depositTxn = Transaction.builder()
                .id(UUID.randomUUID())
                .idempotencyKey(UUID.randomUUID().toString())
                .type(TransactionType.DEPOSIT)
                .status(TransactionStatus.PENDING)
                .receiverWallet(receiverWallet)
                .amount(new BigDecimal("300.00"))
                .currency("INR")
                .retryCount(0)
                .build();

            when(walletRepository.findByIdWithOptimisticLock(receiverWallet.getId()))
                .thenReturn(Optional.of(receiverWallet));
            when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            paymentProcessor.processTransaction(depositTxn);

            assertThat(receiverWallet.getBalance())
                .isEqualByComparingTo(new BigDecimal("800.00"));
            assertThat(depositTxn.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
            verify(ledgerService).recordCredit(eq(receiverWallet), eq(depositTxn),
                eq(new BigDecimal("300.00")), anyString());
        }
    }

    @Nested
    @DisplayName("Wallet Business Logic Tests")
    class WalletTests {

        @Test
        @DisplayName("Available balance = balance - reserved")
        void wallet_availableBalance_calculatedCorrectly() {
            senderWallet = Wallet.builder()
                .balance(new BigDecimal("1000.00"))
                .reservedBalance(new BigDecimal("200.00"))
                .build();

            assertThat(senderWallet.getAvailableBalance())
                .isEqualByComparingTo(new BigDecimal("800.00"));
        }

        @Test
        @DisplayName("hasSufficientBalance uses available balance, not total balance")
        void wallet_hasSufficientBalance_usesAvailableBalance() {
            senderWallet = Wallet.builder()
                .balance(new BigDecimal("1000.00"))
                .reservedBalance(new BigDecimal("800.00"))  // Only 200 available
                .build();

            assertThat(senderWallet.hasSufficientBalance(new BigDecimal("300.00"))).isFalse();
            assertThat(senderWallet.hasSufficientBalance(new BigDecimal("150.00"))).isTrue();
        }
    }
}
