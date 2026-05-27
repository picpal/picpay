package com.picpay.billing.service;

import com.picpay.billing.dto.BillingHistoryResponse;
import com.picpay.billing.repository.BillingHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class BillingHistoryService {

    private final BillingHistoryRepository billingHistoryRepository;

    public BillingHistoryService(BillingHistoryRepository billingHistoryRepository) {
        this.billingHistoryRepository = billingHistoryRepository;
    }

    @Transactional(readOnly = true)
    public List<BillingHistoryResponse> findByPlanId(String planId) {
        return billingHistoryRepository.findByPlanIdOrderByCreatedAtDesc(planId)
                .stream()
                .map(BillingHistoryResponse::from)
                .toList();
    }
}
