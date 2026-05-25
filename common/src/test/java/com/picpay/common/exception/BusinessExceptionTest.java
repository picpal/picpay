package com.picpay.common.exception;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

    @Test
    void BusinessException_errorCode_보존() {
        BusinessException ex = new BusinessException(ErrorCode.PAYMENT_NOT_FOUND);

        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.PAYMENT_NOT_FOUND.getMessage());
    }
}
