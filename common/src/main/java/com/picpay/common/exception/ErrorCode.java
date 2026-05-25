package com.picpay.common.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    DUPLICATE_ORDER("DUPLICATE_ORDER", "Duplicate orderId", HttpStatus.CONFLICT),
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", "Payment not found", HttpStatus.NOT_FOUND),
    INVALID_STATUS_TRANSITION("INVALID_STATUS_TRANSITION", "Invalid status transition", HttpStatus.BAD_REQUEST),
    TOKEN_NOT_FOUND("TOKEN_NOT_FOUND", "Token not found", HttpStatus.NOT_FOUND),
    PLAN_NOT_FOUND("PLAN_NOT_FOUND", "Billing plan not found", HttpStatus.NOT_FOUND),
    RATE_LIMIT_EXCEEDED("RATE_LIMIT_EXCEEDED", "API rate limit exceeded", HttpStatus.TOO_MANY_REQUESTS),
    UNAUTHORIZED("UNAUTHORIZED", "Authentication failed", HttpStatus.UNAUTHORIZED),
    IDEMPOTENCY_CONFLICT("IDEMPOTENCY_CONFLICT", "Idempotency key conflict", HttpStatus.CONFLICT),
    INTERNAL_ERROR("INTERNAL_ERROR", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;

    ErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    public String getCode() { return code; }
    public String getMessage() { return message; }
    public HttpStatus getStatus() { return status; }
}
