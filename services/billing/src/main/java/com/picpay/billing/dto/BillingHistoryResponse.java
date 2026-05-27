package com.picpay.billing.dto;

import com.picpay.billing.domain.BillingHistory;
import java.time.LocalDateTime;

public record BillingHistoryResponse(
        Long id,
        String planId,
        String tid,
        Long amount,
        String status,
        String failureReason,
        LocalDateTime createdAt
) {
    public static BillingHistoryResponse from(BillingHistory history) {
        return new BillingHistoryResponse(
                history.getId(),
                history.getPlanId(),
                history.getTid(),
                history.getAmount(),
                history.getStatus(),
                history.getFailureReason(),
                history.getCreatedAt()
        );
    }
}
