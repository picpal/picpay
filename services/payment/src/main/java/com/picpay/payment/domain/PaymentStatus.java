package com.picpay.payment.domain;

public enum PaymentStatus {
    READY, PAID, CANCELLED, PARTIAL_CANCELLED, FAILED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case READY -> next == PAID || next == FAILED;
            case PAID -> next == CANCELLED || next == PARTIAL_CANCELLED;
            case PARTIAL_CANCELLED -> next == CANCELLED || next == PARTIAL_CANCELLED;
            default -> false;
        };
    }
}
