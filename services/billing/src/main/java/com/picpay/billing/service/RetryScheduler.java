package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingRetryJob;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final BillingRetryJobRepository billingRetryJobRepository;
    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PaymentClient paymentClient;

    public RetryScheduler(BillingRetryJobRepository billingRetryJobRepository,
                           BillingPlanRepository billingPlanRepository,
                           BillingHistoryRepository billingHistoryRepository,
                           PaymentClient paymentClient) {
        this.billingRetryJobRepository = billingRetryJobRepository;
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.paymentClient = paymentClient;
    }

    @Scheduled(fixedDelay = 10000)
    public void execute() {
        List<BillingRetryJob> jobs = billingRetryJobRepository.findDueRetryJobs(LocalDateTime.now());
        for (BillingRetryJob job : jobs) {
            processRetryJob(job);
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
