package com.picpay.payment.dto;

import com.picpay.payment.domain.PartialCancellation;

public record CancelResponse(
        String cancelTid,
        String status,
        Long cancelAmount,
        Long remainingAmount,
        String reason
) {
    public static CancelResponse from(PartialCancellation pc) {
        return new CancelResponse(
                pc.getCancelTid(),
                pc.getStatus(),
                pc.getCancelAmount(),
                pc.getRemainingAmount(),
                pc.getReason()
        );
    }
}
