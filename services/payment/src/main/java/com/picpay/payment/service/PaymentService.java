package com.picpay.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.payment.domain.OutboxEvent;
import com.picpay.payment.domain.PartialCancellation;
import com.picpay.payment.domain.Payment;
import com.picpay.payment.dto.CancelRequest;
import com.picpay.payment.dto.CancelResponse;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.pg.MockPgClient;
import com.picpay.payment.repository.OutboxEventRepository;
import com.picpay.payment.repository.PartialCancellationRepository;
import com.picpay.payment.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TidService tidService;
    private final MockPgClient mockPgClient;
    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PartialCancellationRepository partialCancellationRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public PaymentService(TidService tidService, MockPgClient mockPgClient,
                          PaymentRepository paymentRepository,
                          OutboxEventRepository outboxEventRepository,
                          PartialCancellationRepository partialCancellationRepository,
                          StringRedisTemplate redisTemplate,
                          ObjectMapper objectMapper) {
        this.tidService = tidService;
        this.mockPgClient = mockPgClient;
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.partialCancellationRepository = partialCancellationRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentResponse approve(PaymentRequest request) {
        if (request.idempotencyKey() != null) {
            String cached = redisTemplate.opsForValue()
                    .get(IDEMPOTENCY_PREFIX + request.idempotencyKey());
            if (cached != null) {
                try {
                    return objectMapper.readValue(cached, PaymentResponse.class);
                } catch (JsonProcessingException e) {
                    log.warn("Idempotency cache deserialization failed: {}", e.getMessage());
                }
            }
        }

        String tid = tidService.generate();
        MockPgClient.PgApprovalResult pgResult = mockPgClient.approve(tid, request.amount());

        Payment payment = Payment.create(tid, request.merchantId(), request.orderId(),
                request.tokenId(), request.amount(), request.method(), request.idempotencyKey());

        if (pgResult.approved()) {
            payment.approve(pgResult.pgTid());
        } else {
            payment.fail();
        }

        Payment saved = paymentRepository.save(payment);

        String eventType = pgResult.approved() ? "payment.completed" : "payment.failed";
        String topic = pgResult.approved() ? "payment.completed" : "payment.failed";
        outboxEventRepository.save(OutboxEvent.create(
                "Payment", saved.getTid(), eventType, topic, toJson(saved)));

        PaymentResponse response = PaymentResponse.from(saved);

        if (request.idempotencyKey() != null) {
            try {
                redisTemplate.opsForValue().set(
                        IDEMPOTENCY_PREFIX + request.idempotencyKey(),
                        objectMapper.writeValueAsString(response),
                        IDEMPOTENCY_TTL);
            } catch (JsonProcessingException e) {
                log.warn("Idempotency cache serialization failed: {}", e.getMessage());
            }
        }

        return response;
    }

    @Transactional(readOnly = true)
    public PaymentResponse findByTid(String tid) {
        Payment payment = paymentRepository.findByTid(tid)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
        return PaymentResponse.from(payment);
    }

    @Transactional
    public CancelResponse cancel(CancelRequest request) {
        Payment payment = paymentRepository.findByTidWithLock(request.tid())
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        List<PartialCancellation> existing = partialCancellationRepository.findByPaymentId(payment.getId());
        long alreadyCancelled = existing.stream().mapToLong(PartialCancellation::getCancelAmount).sum();
        long remainingAfter = payment.getAmount() - alreadyCancelled - request.cancelAmount();

        if (remainingAfter < 0) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }

        MockPgClient.PgApprovalResult pgResult = mockPgClient.cancel(payment.getPgTid(), request.cancelAmount());

        String cancelTid = "C" + tidService.generate().substring(1);

        if (remainingAfter == 0) {
            payment.cancel();
        } else {
            payment.partialCancel();
        }
        paymentRepository.save(payment);

        PartialCancellation pc = PartialCancellation.create(
                payment.getId(), cancelTid, request.cancelAmount(),
                remainingAfter, request.reason(), pgResult.pgTid());
        partialCancellationRepository.save(pc);

        outboxEventRepository.save(OutboxEvent.create(
                "Payment", payment.getTid(), "payment.cancelled", "payment.cancelled",
                toJson(payment)));

        return CancelResponse.from(pc);
    }

    private String toJson(Payment payment) {
        try {
            return objectMapper.writeValueAsString(PaymentResponse.from(payment));
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
