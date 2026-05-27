package com.picpay.billing.controller;

import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.service.BillingPlanService;
import com.picpay.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final BillingPlanService billingPlanService;

    public BillingController(BillingPlanService billingPlanService) {
        this.billingPlanService = billingPlanService;
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BillingPlanResponse> createPlan(@Valid @RequestBody CreateBillingPlanRequest request) {
        return ApiResponse.ok(billingPlanService.create(request));
    }

    @GetMapping("/plans/{planId}")
    public ApiResponse<BillingPlanResponse> getPlan(@PathVariable String planId) {
        return ApiResponse.ok(billingPlanService.findByPlanId(planId));
    }

    @DeleteMapping("/plans/{planId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelPlan(@PathVariable String planId) {
        billingPlanService.cancel(planId);
    }
}
