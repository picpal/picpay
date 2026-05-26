package com.picpay.payment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.common.exception.GlobalExceptionHandler;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
@Import(GlobalExceptionHandler.class)
class PaymentControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean PaymentService paymentService;

    @Test
    void post_payments_returns201_withPaidStatus() throws Exception {
        PaymentResponse response = new PaymentResponse(
                "TSVR01tid001", "mer_001", "order-001", "tok_abc",
                10000L, "CARD", "PAID", "PG-001", null);
        when(paymentService.approve(any())).thenReturn(response);

        PaymentRequest request = new PaymentRequest(
                "mer_001", "order-001", "tok_abc", 10000L, "CARD", null);

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    void post_payments_missingMerchantId_returns400() throws Exception {
        String body = """
                {"orderId":"order-001","tokenId":"tok_abc","amount":10000,"method":"CARD"}
                """;

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_payments_tid_notFound_returns404() throws Exception {
        when(paymentService.findByTid(eq("unknown-tid")))
                .thenThrow(new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));

        mockMvc.perform(get("/v1/payments/unknown-tid"))
                .andExpect(status().isNotFound());
    }
}
