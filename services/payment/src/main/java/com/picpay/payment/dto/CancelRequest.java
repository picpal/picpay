package com.picpay.payment.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CancelRequest(
        @NotBlank String tid,
        @NotNull @Min(1) Long cancelAmount,
        String reason
) {}
