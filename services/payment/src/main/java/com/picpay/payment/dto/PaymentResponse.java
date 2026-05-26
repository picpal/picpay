package com.picpay.payment.dto;

import com.picpay.payment.domain.Payment;

import java.time.LocalDateTime;

public record PaymentResponse(
        String tid,
        String merchantId,
        String orderId,
        String tokenId,
        Long amount,
        String method,
        String status,
        String pgTid,
        LocalDateTime createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getTid(),
                payment.getMerchantId(),
                payment.getOrderId(),
                payment.getTokenId(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getStatus().name(),
                payment.getPgTid(),
                payment.getCreatedAt()
        );
    }
}
