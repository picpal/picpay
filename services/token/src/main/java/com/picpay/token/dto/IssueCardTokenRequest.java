package com.picpay.token.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IssueCardTokenRequest(
        @NotBlank String merchantId,
        @NotBlank @Size(min = 13, max = 19) String cardNumber,
        @NotBlank String cardExpiry,
        @NotBlank @Size(min = 3, max = 4) String cardCvc
) {}
