package com.picpay.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

class ErrorCodeTest {

    @Test
    void PAYMENT_NOT_FOUND_속성_확인() {
        assertThat(ErrorCode.PAYMENT_NOT_FOUND.getCode()).isEqualTo("PAYMENT_NOT_FOUND");
        assertThat(ErrorCode.PAYMENT_NOT_FOUND.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void RATE_LIMIT_EXCEEDED_속성_확인() {
        assertThat(ErrorCode.RATE_LIMIT_EXCEEDED.getCode()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(ErrorCode.RATE_LIMIT_EXCEEDED.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
