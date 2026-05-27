package com.picpay.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;

public record CreateBillingPlanRequest(
        @NotBlank String merchantId,
        @NotBlank String tokenId,
        @NotNull @Min(1) Long amount,
        @NotBlank String cycle,
        @NotNull LocalDateTime nextBillingAt
) {}
