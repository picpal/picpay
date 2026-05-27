package com.picpay.billing.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.billing.dto.BillingHistoryResponse;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.service.BillingHistoryService;
import com.picpay.billing.service.BillingPlanService;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BillingController.class)
@Import(GlobalExceptionHandler.class)
class BillingControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean BillingPlanService billingPlanService;
    @MockitoBean BillingHistoryService billingHistoryService;

    @Test
    void post_billingPlans_returns201WithActiveStatus() throws Exception {
        BillingPlanResponse response = new BillingPlanResponse(
                "BP-001", "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0), "ACTIVE", 0, LocalDateTime.now());
        when(billingPlanService.create(any())).thenReturn(response);

        CreateBillingPlanRequest request = new CreateBillingPlanRequest(
                "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0));

        mockMvc.perform(post("/v1/billing/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void post_billingPlans_missingMerchantId_returns400() throws Exception {
        String body = """
                {"tokenId":"tok_abc","amount":10000,"cycle":"MONTHLY",
                 "nextBillingAt":"2026-06-01T00:00:00"}
                """;
        mockMvc.perform(post("/v1/billing/plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void get_billingPlans_planId_notFound_returns404() throws Exception {
        when(billingPlanService.findByPlanId("unknown"))
                .thenThrow(new BusinessException(ErrorCode.PLAN_NOT_FOUND));

        mockMvc.perform(get("/v1/billing/plans/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void delete_billingPlans_planId_returns204() throws Exception {
        doNothing().when(billingPlanService).cancel("BP-001");

        mockMvc.perform(delete("/v1/billing/plans/BP-001"))
                .andExpect(status().isNoContent());
    }

    @Test
    void get_billingPlans_planId_history_returnsHistoryList() throws Exception {
        BillingHistoryResponse entry = new BillingHistoryResponse(
                1L, "BP-001", "TXN-001", 10000L, "SUCCESS", null, LocalDateTime.now());
        when(billingHistoryService.findByPlanId("BP-001")).thenReturn(List.of(entry));

        mockMvc.perform(get("/v1/billing/plans/BP-001/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$.data[0].tid").value("TXN-001"));
    }
}
