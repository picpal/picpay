package com.picpay.billing.service;

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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);
    private static final long LOCK_TTL_SECONDS = 30;

    private final BillingRetryJobRepository billingRetryJobRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PaymentClient paymentClient;
    private final RedissonClient redissonClient;

    public RetryScheduler(BillingRetryJobRepository billingRetryJobRepository,
                           BillingPlanRepository billingPlanRepository,
                           BillingHistoryRepository billingHistoryRepository,
                           PaymentClient paymentClient,
                           RedissonClient redissonClient) {
        this.billingRetryJobRepository = billingRetryJobRepository;
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.paymentClient = paymentClient;
        this.redissonClient = redissonClient;
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
                log.warn("[Retry] Max retries exhausted, plan PAUSED: planId={}", job.getPlanId());
            } else {
                job.prepareNextRetry(reason);
                billingRetryJobRepository.save(job);
            }
        }
    }
}
