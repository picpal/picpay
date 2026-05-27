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
import org.springframework.data.domain.PageRequest;
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
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);
    private static final long LOCK_TTL_SECONDS = 30;
    private static final String TOPIC = "billing.executed";

    private final BillingRetryJobRepository billingRetryJobRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PaymentClient paymentClient;
    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public RetryScheduler(BillingRetryJobRepository billingRetryJobRepository,
                           BillingPlanRepository billingPlanRepository,
                           BillingHistoryRepository billingHistoryRepository,
                           PaymentClient paymentClient,
                           RedissonClient redissonClient,
                           KafkaTemplate<String, String> kafkaTemplate,
                           ObjectMapper objectMapper) {
        this.billingRetryJobRepository = billingRetryJobRepository;
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.paymentClient = paymentClient;
        this.redissonClient = redissonClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 10000)
    public void execute() {
        List<BillingRetryJob> jobs = billingRetryJobRepository.findDueRetryJobs(
                LocalDateTime.now(), PageRequest.of(0, 100));
        for (BillingRetryJob job : jobs) {
            String lockKey = "lock:billing:" + job.getPlanId();
            RLock lock = redissonClient.getLock(lockKey);
            boolean acquired = false;
            try {
                acquired = lock.tryLock(0, LOCK_TTL_SECONDS, TimeUnit.SECONDS);
                if (!acquired) {
                    log.info("[Retry] Lock not acquired, skipping: planId={}", job.getPlanId());
                    continue;
                }
                processRetryJob(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Retry] Interrupted acquiring lock: planId={}", job.getPlanId());
                return;
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    }

    void processRetryJob(BillingRetryJob job) {
        BillingPlan plan = billingPlanRepository.findByPlanId(job.getPlanId()).orElse(null);
        if (plan == null || plan.getStatus() != BillingStatus.ACTIVE) {
            job.markDead();
            billingRetryJobRepository.save(job);
            return;
        }

        String orderId = "RETRY-" + job.getId() + "-" + job.getRetryCount();
        try {
            String tid = paymentClient.requestPayment(
                    plan.getMerchantId(), orderId, plan.getTokenId(), plan.getAmount());

            plan.advanceNextBillingAt();
            billingPlanRepository.save(plan);
            billingHistoryRepository.save(
                    BillingHistory.success(plan.getPlanId(), tid, plan.getAmount()));
            job.markDone();
            billingRetryJobRepository.save(job);

            publishEvent(plan.getPlanId(), tid, plan.getAmount(), "SUCCESS", null);
            log.info("[Retry] Success: planId={}, retryCount={}, tid={}",
                    job.getPlanId(), job.getRetryCount(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Retry] Failed: planId={}, retryCount={}",
                    job.getPlanId(), job.getRetryCount(), e);

            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));

            if (job.isExhaustedAfterIncrement()) {
                job.prepareNextRetry(reason);
                job.markDead();
                plan.pause();
                billingPlanRepository.save(plan);
                billingRetryJobRepository.save(job);
                publishEvent(plan.getPlanId(), null, plan.getAmount(), "FAILED", reason);
                log.warn("[Retry] Max retries exhausted, plan PAUSED: planId={}", job.getPlanId());
            } else {
                job.prepareNextRetry(reason);
                billingRetryJobRepository.save(job);
            }
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
            log.error("[Retry] Failed to publish Kafka event: planId={}", planId, e);
        }
    }
}
