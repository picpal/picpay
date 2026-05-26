package com.picpay.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PaymentRequest(
        @NotBlank String merchantId,
        @NotBlank String orderId,
        @NotBlank String tokenId,
        @NotNull @Min(1) Long amount,
        @NotBlank String method,
        String idempotencyKey
) {}
