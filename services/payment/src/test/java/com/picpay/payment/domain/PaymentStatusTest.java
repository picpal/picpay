package com.picpay.payment.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    void ready_canTransitionTo_paidOrFailed() {
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.PAID)).isTrue();
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.FAILED)).isTrue();
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.CANCELLED)).isFalse();
    }

    @Test
    void paid_canTransitionTo_cancelledOrPartialCancelled() {
        assertThat(PaymentStatus.PAID.canTransitionTo(PaymentStatus.CANCELLED)).isTrue();
        assertThat(PaymentStatus.PAID.canTransitionTo(PaymentStatus.PARTIAL_CANCELLED)).isTrue();
        assertThat(PaymentStatus.PAID.canTransitionTo(PaymentStatus.READY)).isFalse();
    }

    @Test
    void failed_cannotTransitionToAnything() {
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.PAID)).isFalse();
        assertThat(PaymentStatus.FAILED.canTransitionTo(PaymentStatus.CANCELLED)).isFalse();
    }
}
