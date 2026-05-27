package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class BillingPlanService {

    private final BillingPlanRepository billingPlanRepository;

    public BillingPlanService(BillingPlanRepository billingPlanRepository) {
        this.billingPlanRepository = billingPlanRepository;
    }

    @Transactional
    public BillingPlanResponse create(CreateBillingPlanRequest request) {
        String planId = "BP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        BillingPlan plan = BillingPlan.of(
                planId,
                request.merchantId(),
                request.tokenId(),
                request.amount(),
                request.cycle(),
                request.nextBillingAt()
        );
        return BillingPlanResponse.from(billingPlanRepository.save(plan));
    }

    @Transactional(readOnly = true)
    public BillingPlanResponse findByPlanId(String planId) {
        return BillingPlanResponse.from(getOrThrow(planId));
    }

    @Transactional
    public void cancel(String planId) {
        BillingPlan plan = getOrThrow(planId);
        plan.cancel();
        billingPlanRepository.save(plan);
    }

    public BillingPlan getOrThrow(String planId) {
        return billingPlanRepository.findByPlanId(planId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PLAN_NOT_FOUND));
    }
}
