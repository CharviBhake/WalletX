package com.fintech.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.payment.dto.AuthDtos;
import com.fintech.payment.dto.DepositRequest;
import com.fintech.payment.dto.TransferRequest;
import com.fintech.payment.dto.WithdrawRequest;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.entity.Wallet;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.repository.WalletRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full integration test suite.
 *
 * Uses H2 in-memory DB. RabbitMQ and Redis are mocked/disabled via test profile.
 * Tests the complete HTTP → Service → DB flow.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentSystemIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WalletRepository walletRepository;
    @Autowired private TransactionRepository transactionRepository;

    // Shared state across ordered tests
    static String aliceToken;
    static String bobToken;
    static String aliceWalletNumber;
    static String bobWalletNumber;

    // ─── Auth ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    @DisplayName("POST /auth/register — Alice registers and gets JWT + wallet")
    void register_alice_createsUserAndWallet() throws Exception {
        AuthDtos.RegisterRequest req = AuthDtos.RegisterRequest.builder()
            .username("alice_test")
            .email("alice@integrationtest.com")
            .password("Password123!")
            .fullName("Alice Test")
            .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.username").value("alice_test"))
            .andExpect(jsonPath("$.walletId").isNotEmpty())
            .andReturn();

        AuthDtos.AuthResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(), AuthDtos.AuthResponse.class);
        aliceToken = response.getToken();

        // Verify wallet created in DB
        Wallet wallet = walletRepository.findById(response.getWalletId()).orElseThrow();
        assertThat(wallet.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(wallet.getWalletNumber()).startsWith("WL-");
        aliceWalletNumber = wallet.getWalletNumber();
    }

    @Test
    @Order(2)
    @DisplayName("POST /auth/register — Bob registers")
    void register_bob() throws Exception {
        AuthDtos.RegisterRequest req = AuthDtos.RegisterRequest.builder()
            .username("bob_test")
            .email("bob@integrationtest.com")
            .password("Password123!")
            .fullName("Bob Test")
            .build();

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andReturn();

        AuthDtos.AuthResponse response = objectMapper.readValue(
            result.getResponse().getContentAsString(), AuthDtos.AuthResponse.class);
        bobToken = response.getToken();
        bobWalletNumber = walletRepository.findById(response.getWalletId())
            .orElseThrow().getWalletNumber();
    }

    @Test
    @Order(3)
    @DisplayName("POST /auth/register — duplicate username returns 409")
    void register_duplicateUsername_returns409() throws Exception {
        AuthDtos.RegisterRequest req = AuthDtos.RegisterRequest.builder()
            .username("alice_test")  // Already taken
            .email("alice2@test.com")
            .password("Password123!")
            .fullName("Alice Clone")
            .build();

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("USERNAME_TAKEN"));
    }

    // ─── Wallet ───────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    @DisplayName("GET /wallet/me — returns wallet with zero balance")
    void getMyWallet_returnsCorrectWallet() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/me")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.balance").value(0))
            .andExpect(jsonPath("$.currency").value("INR"))
            .andExpect(jsonPath("$.active").value(true));
    }

    // ─── Payments ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    @DisplayName("POST /payments/deposit — creates PENDING transaction")
    void deposit_createsTransaction() throws Exception {
        DepositRequest req = DepositRequest.builder()
            .amount(new BigDecimal("5000.00"))
            .description("Test deposit")
            .build();

        String idempotencyKey = UUID.randomUUID().toString();

        MvcResult result = mockMvc.perform(post("/api/v1/payments/deposit")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.type").value("DEPOSIT"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.amount").value(5000.00))
            .andExpect(jsonPath("$.duplicate").value(false))
            .andReturn();
    }

    @Test
    @Order(6)
    @DisplayName("POST /payments/deposit — idempotent: same key returns duplicate=true")
    void deposit_sameIdempotencyKey_returnsDuplicate() throws Exception {
        DepositRequest req = DepositRequest.builder()
            .amount(new BigDecimal("1000.00"))
            .description("Idempotency test")
            .build();

        String idempotencyKey = UUID.randomUUID().toString();

        // First request
        mockMvc.perform(post("/api/v1/payments/deposit")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.duplicate").value(false));

        // Second request — same key
        mockMvc.perform(post("/api/v1/payments/deposit")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.duplicate").value(true));

        // Verify only ONE transaction was created for this key
        long count = transactionRepository.findAll().stream()
            .filter(t -> idempotencyKey.equals(t.getIdempotencyKey()))
            .count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("POST /payments/withdraw — insufficient balance returns 422")
    void withdraw_insufficientBalance_returns422() throws Exception {
        WithdrawRequest req = WithdrawRequest.builder()
            .amount(new BigDecimal("99999.00"))
            .build();

        mockMvc.perform(post("/api/v1/payments/withdraw")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    @Order(8)
    @DisplayName("POST /payments/transfer — self-transfer returns 400")
    void transfer_selfTransfer_returns400() throws Exception {
        TransferRequest req = TransferRequest.builder()
            .receiverWalletNumber(aliceWalletNumber)  // Alice → Alice
            .amount(new BigDecimal("100.00"))
            .build();

        mockMvc.perform(post("/api/v1/payments/transfer")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("SELF_TRANSFER_NOT_ALLOWED"));
    }

    @Test
    @Order(9)
    @DisplayName("POST /payments/transfer — invalid wallet number returns 404")
    void transfer_unknownWallet_returns404() throws Exception {
        TransferRequest req = TransferRequest.builder()
            .receiverWalletNumber("WL-DOESNOTEXIST")
            .amount(new BigDecimal("100.00"))
            .build();

        mockMvc.perform(post("/api/v1/payments/transfer")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("WALLET_NOT_FOUND"));
    }

    @Test
    @Order(10)
    @DisplayName("POST /payments — missing Idempotency-Key header returns 400")
    void deposit_missingIdempotencyKey_returns400() throws Exception {
        DepositRequest req = DepositRequest.builder()
            .amount(new BigDecimal("100.00"))
            .build();

        mockMvc.perform(post("/api/v1/payments/deposit")
                .header("Authorization", "Bearer " + aliceToken)
                // No Idempotency-Key header
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }

    @Test
    @Order(11)
    @DisplayName("GET /payments/history — returns paginated transaction list")
    void getHistory_returnsPaginatedResults() throws Exception {
        mockMvc.perform(get("/api/v1/payments/history?page=0&size=10")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.totalElements").isNumber());
    }

    @Test
    @Order(12)
    @DisplayName("GET /wallet/me — unauthenticated request returns 401/403")
    void getWallet_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/me"))
            .andExpect(status().isForbidden());
    }

    // ─── Input validation ─────────────────────────────────────────────────────

    @Test
    @Order(13)
    @DisplayName("POST /payments/deposit — negative amount fails validation")
    void deposit_negativeAmount_returns400() throws Exception {
        String body = "{\"amount\": -100.00}";

        mockMvc.perform(post("/api/v1/payments/deposit")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.fieldErrors.amount").exists());
    }

    @Test
    @Order(14)
    @DisplayName("POST /payments/deposit — amount over limit fails validation")
    void deposit_overMaxAmount_returns400() throws Exception {
        String body = "{\"amount\": 999999.00}";

        mockMvc.perform(post("/api/v1/payments/deposit")
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    // ─── Ledger ───────────────────────────────────────────────────────────────

    @Test
    @Order(15)
    @DisplayName("GET /ledger/entries — returns ledger page for authenticated user")
    void getLedger_returnsEntries() throws Exception {
        mockMvc.perform(get("/api/v1/ledger/entries?page=0&size=10")
                .header("Authorization", "Bearer " + aliceToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray());
    }
}
