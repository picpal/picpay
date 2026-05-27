package com.picpay.billing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingRetryJob;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);
    private static final long LOCK_TTL_SECONDS = 30;
    private static final String TOPIC = "billing.executed";

    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final BillingRetryJobRepository billingRetryJobRepository;
    private final PaymentClient paymentClient;
    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public BillingScheduler(BillingPlanRepository billingPlanRepository,
                             BillingHistoryRepository billingHistoryRepository,
                             BillingRetryJobRepository billingRetryJobRepository,
                             PaymentClient paymentClient,
                             RedissonClient redissonClient,
                             KafkaTemplate<String, String> kafkaTemplate,
                             ObjectMapper objectMapper) {
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.billingRetryJobRepository = billingRetryJobRepository;
        this.paymentClient = paymentClient;
        this.redissonClient = redissonClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 60000)
    public void execute() {
        List<BillingPlan> duePlans = billingPlanRepository.findDuePlans(
                BillingStatus.ACTIVE, LocalDateTime.now());

        for (BillingPlan plan : duePlans) {
            String lockKey = "lock:billing:" + plan.getPlanId();
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                if (!acquired) {
                    log.info("[Billing] Lock not acquired, skipping: planId={}", plan.getPlanId());
                    continue;
                }
                processPlan(plan);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Billing] Interrupted acquiring lock: planId={}", plan.getPlanId());
                return;
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    void processPlan(BillingPlan plan) {
        String orderId = "BILL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        try {
            String tid = paymentClient.requestPayment(
                    plan.getMerchantId(), orderId, plan.getTokenId(), plan.getAmount());

            plan.advanceNextBillingAt();
            billingPlanRepository.save(plan);
            billingHistoryRepository.save(
                    BillingHistory.success(plan.getPlanId(), tid, plan.getAmount()));

            publishEvent(plan.getPlanId(), tid, plan.getAmount(), "SUCCESS", null);
            log.info("[Billing] Success: planId={}, tid={}", plan.getPlanId(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Billing] Failed: planId={}", plan.getPlanId(), e);

            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));
            billingRetryJobRepository.save(BillingRetryJob.create(plan.getPlanId(), reason));

            publishEvent(plan.getPlanId(), null, plan.getAmount(), "FAILED", reason);
        }
    }

    void publishEvent(String planId, String tid, Long amount, String status, String failureReason) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("planId", planId);
            data.put("tid", tid);
            data.put("amount", amount);
            data.put("status", status);
            data.put("failureReason", failureReason);

            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventId", "evt-" + UUID.randomUUID());
            event.put("eventType", "BILLING_EXECUTED");
            event.put("timestamp", LocalDateTime.now().toString());
            event.put("data", data);

            kafkaTemplate.send(TOPIC, planId, objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.error("[Billing] Failed to publish Kafka event: planId={}", planId, e);
        }
    }
}
