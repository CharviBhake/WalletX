package com.fintech.payment;

import com.fintech.payment.dto.TransactionResponse;
import com.fintech.payment.entity.IdempotencyRecord;
import com.fintech.payment.entity.Transaction;
import com.fintech.payment.enums.TransactionStatus;
import com.fintech.payment.enums.TransactionType;
import com.fintech.payment.exception.PaymentException;
import com.fintech.payment.repository.IdempotencyRecordRepository;
import com.fintech.payment.repository.TransactionRepository;
import com.fintech.payment.service.IdempotencyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("IdempotencyService Tests")
class IdempotencyServiceTest {

    @Mock private RedisTemplate<String, Object>   redisTemplate;
    @Mock private ValueOperations<String, Object>  valueOps;
    @Mock private IdempotencyRecordRepository      idempotencyRecordRepository;
    @Mock private TransactionRepository            transactionRepository;

    @InjectMocks private IdempotencyService idempotencyService;

    @Test
    @DisplayName("Redis HIT returns cached response immediately")
    void checkIdempotency_redisHit_returnsCachedResponse() {
        String key = UUID.randomUUID().toString();
        TransactionResponse cached = TransactionResponse.builder()
            .id(UUID.randomUUID()).status(TransactionStatus.SUCCESS).build();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("idempotency:" + key)).thenReturn(cached);

        var result = idempotencyService.checkIdempotency(key, null);

        assertThat(result).isPresent();
        assertThat(result.get().isDuplicate()).isTrue();
        verifyNoInteractions(idempotencyRecordRepository);
    }

    @Test
    @DisplayName("Redis MISS, DB HIT returns response and repopulates Redis")
    void checkIdempotency_dbHit_repopulatesRedisAndReturns() {
        String key = UUID.randomUUID().toString();
        UUID txnId = UUID.randomUUID();

        IdempotencyRecord record = IdempotencyRecord.builder()
            .idempotencyKey(key)
            .transactionId(txnId)
            .requestHash("abc123")
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();

        Transaction txn = Transaction.builder()
            .id(txnId)
            .idempotencyKey(key)
            .type(TransactionType.DEPOSIT)
            .status(TransactionStatus.SUCCESS)
            .amount(new BigDecimal("100.00"))
            .currency("INR")
            .retryCount(0)
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(idempotencyRecordRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(txn));

        var result = idempotencyService.checkIdempotency(key, "abc123");

        assertThat(result).isPresent();
        assertThat(result.get().isDuplicate()).isTrue();
        verify(valueOps).set(eq("idempotency:" + key), any(), any());
    }

    @Test
    @DisplayName("Key reuse with different payload throws PaymentException")
    void checkIdempotency_keyReuseWithDifferentHash_throwsException() {
        String key = UUID.randomUUID().toString();

        IdempotencyRecord record = IdempotencyRecord.builder()
            .idempotencyKey(key)
            .transactionId(UUID.randomUUID())
            .requestHash("original-hash")
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(idempotencyRecordRepository.findByIdempotencyKey(key)).thenReturn(Optional.of(record));

        assertThatThrownBy(() ->
            idempotencyService.checkIdempotency(key, "different-hash"))
            .isInstanceOf(PaymentException.class)
            .hasMessageContaining("different request payload");
    }

    @Test
    @DisplayName("Cache MISS on both layers returns empty Optional")
    void checkIdempotency_cacheMiss_returnsEmpty() {
        String key = UUID.randomUUID().toString();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(idempotencyRecordRepository.findByIdempotencyKey(key)).thenReturn(Optional.empty());

        var result = idempotencyService.checkIdempotency(key, "hash");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("computeHash produces consistent SHA-256 for same input")
    void computeHash_sameInput_producesConsistentHash() {
        String body = "{\"amount\":100.00}";
        String hash1 = idempotencyService.computeHash(body);
        String hash2 = idempotencyService.computeHash(body);
        assertThat(hash1).isEqualTo(hash2).hasSize(64);
    }
}
