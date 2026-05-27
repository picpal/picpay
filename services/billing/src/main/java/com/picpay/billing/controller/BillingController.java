package com.picpay.billing.controller;

import com.picpay.billing.dto.BillingHistoryResponse;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.service.BillingHistoryService;
import com.picpay.billing.service.BillingPlanService;
import com.picpay.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final BillingPlanService billingPlanService;
    private final BillingHistoryService billingHistoryService;

    public BillingController(BillingPlanService billingPlanService,
                              BillingHistoryService billingHistoryService) {
        this.billingPlanService = billingPlanService;
        this.billingHistoryService = billingHistoryService;
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

    @GetMapping("/plans/{planId}/history")
    public ApiResponse<List<BillingHistoryResponse>> getHistory(@PathVariable String planId) {
        return ApiResponse.ok(billingHistoryService.findByPlanId(planId));
    }
}
