package com.picpay.common.exception;

import com.picpay.common.response.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void BusinessException_처리_시_해당_상태코드_반환() {
        BusinessException ex = new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);

        ResponseEntity<ApiResponse<?>> response = handler.handleBusinessException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void 알수없는_예외_처리_시_500_반환() {
        Exception ex = new RuntimeException("unexpected");

        ResponseEntity<ApiResponse<?>> response = handler.handleException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_ERROR");
    }
}
