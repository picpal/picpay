package com.picpay.billing.dto;

import com.picpay.billing.domain.BillingPlan;
import java.time.LocalDateTime;

public record BillingPlanResponse(
        String planId,
        String merchantId,
        String tokenId,
        Long amount,
        String cycle,
        LocalDateTime nextBillingAt,
        String status,
        int retryCount,
        LocalDateTime createdAt
) {
    public static BillingPlanResponse from(BillingPlan plan) {
        return new BillingPlanResponse(
                plan.getPlanId(),
                plan.getMerchantId(),
                plan.getTokenId(),
                plan.getAmount(),
                plan.getCycle(),
                plan.getNextBillingAt(),
                plan.getStatus().name(),
                plan.getRetryCount(),
                plan.getCreatedAt()
        );
    }
}
