package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Component
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingPlanRepository billingPlanRepository;
    private final BillingHistoryRepository billingHistoryRepository;
    private final PaymentClient paymentClient;

    public BillingScheduler(BillingPlanRepository billingPlanRepository,
                             BillingHistoryRepository billingHistoryRepository,
                             PaymentClient paymentClient) {
        this.billingPlanRepository = billingPlanRepository;
        this.billingHistoryRepository = billingHistoryRepository;
        this.paymentClient = paymentClient;
    }

    @Scheduled(fixedDelay = 60000)
    public void execute() {
        List<BillingPlan> duePlans = billingPlanRepository.findDuePlans(
                BillingStatus.ACTIVE, LocalDateTime.now());

        for (BillingPlan plan : duePlans) {
            processPlan(plan);
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

            log.info("[Billing] Success: planId={}, tid={}", plan.getPlanId(), tid);
        } catch (Exception e) {
            String reason = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
            log.error("[Billing] Failed: planId={}", plan.getPlanId(), e);
            billingHistoryRepository.save(
                    BillingHistory.failure(plan.getPlanId(), plan.getAmount(), reason));
        }
    }
}
