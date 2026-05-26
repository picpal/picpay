package com.picpay.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.payment.domain.OutboxStatus;
import com.picpay.payment.domain.Payment;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.pg.MockPgClient;
import com.picpay.payment.repository.OutboxEventRepository;
import com.picpay.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private TidService tidService;
    @Mock private MockPgClient mockPgClient;
    @Mock private PaymentRepository paymentRepository;
    @Mock private OutboxEventRepository outboxEventRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private PaymentService paymentService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(tidService, mockPgClient,
                paymentRepository, outboxEventRepository, redisTemplate, objectMapper);
    }

    @Test
    void approve_success_returnsPaidStatus() {
        when(tidService.generate()).thenReturn("TSVR0120260526143022000001");
        when(mockPgClient.approve(anyString(), anyLong()))
                .thenReturn(MockPgClient.PgApprovalResult.success("PG-ABCD1234"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-001", "tok_abc123", 10000L, "CARD", "idem-001");

        PaymentResponse response = paymentService.approve(request);

        assertThat(response.status()).isEqualTo("PAID");
        assertThat(response.pgTid()).isEqualTo("PG-ABCD1234");
        assertThat(response.tid()).isEqualTo("TSVR0120260526143022000001");
    }

    @Test
    void approve_pgFails_returnsFailedStatus() {
        when(tidService.generate()).thenReturn("TSVR0120260526143022000002");
        when(mockPgClient.approve(anyString(), anyLong()))
                .thenReturn(MockPgClient.PgApprovalResult.failure("PG_DECLINED"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-002", "tok_abc123", 10000L, "CARD", "idem-002");

        PaymentResponse response = paymentService.approve(request);

        assertThat(response.status()).isEqualTo("FAILED");
    }

    @Test
    void approve_sameIdempotencyKey_returnsCachedResponse() throws Exception {
        String cachedJson = objectMapper.writeValueAsString(new PaymentResponse(
                "TSVR01tid", "mer_001", "order-001", "tok_abc123",
                10000L, "CARD", "PAID", "PG-001", null));

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("idempotency:idem-001")).thenReturn(cachedJson);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-001", "tok_abc123", 10000L, "CARD", "idem-001");

        PaymentResponse response = paymentService.approve(request);

        assertThat(response.status()).isEqualTo("PAID");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void approve_insertsOutboxEvent_onSuccess() {
        when(tidService.generate()).thenReturn("TSVR0120260526143022000003");
        when(mockPgClient.approve(anyString(), anyLong()))
                .thenReturn(MockPgClient.PgApprovalResult.success("PG-ABCD9999"));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-003", "tok_abc123", 10000L, "CARD", null);

        paymentService.approve(request);

        verify(outboxEventRepository, times(1)).save(argThat(event ->
                "payment.completed".equals(event.getEventType()) &&
                OutboxStatus.PENDING == event.getStatus()
        ));
    }

    @Test
    void findByTid_notFound_throwsBusinessException() {
        when(paymentRepository.findByTid("unknown-tid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.findByTid("unknown-tid"))
                .isInstanceOf(BusinessException.class);
    }
}
