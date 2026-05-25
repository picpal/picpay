package com.picpay.token.dto;

import jakarta.validation.constraints.NotBlank;

public record RegisterEasyPayRequest(
        @NotBlank String userId,
        @NotBlank String tokenId,
        String methodName
) {}
