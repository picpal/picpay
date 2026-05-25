package com.picpay.common.response;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void ok_성공응답_생성() {
        ApiResponse<String> response = ApiResponse.ok("data");

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isEqualTo("data");
        assertThat(response.error()).isNull();
    }

    @Test
    void error_실패응답_생성() {
        ApiResponse<?> response = ApiResponse.error("PAYMENT_NOT_FOUND", "Payment not found");

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(response.error().message()).isEqualTo("Payment not found");
    }
}
