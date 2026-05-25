package com.picpay.token.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.GlobalExceptionHandler;
import com.picpay.token.dto.EasyPayMethodResponse;
import com.picpay.token.dto.RegisterEasyPayRequest;
import com.picpay.token.service.EasyPayMethodService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EasyPayMethodController.class)
@Import(GlobalExceptionHandler.class)
class EasyPayMethodControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EasyPayMethodService easyPayMethodService;

    @Test
    void POST_간편결제_수단_등록_201_반환() throws Exception {
        EasyPayMethodResponse response = new EasyPayMethodResponse(
                "epm_abc", "user_001", "tok_abc", "My Card", "ACTIVE");
        given(easyPayMethodService.register(any())).willReturn(response);

        RegisterEasyPayRequest request = new RegisterEasyPayRequest(
                "user_001", "tok_abc", "My Card");

        mockMvc.perform(post("/v1/easy-pay/methods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.methodId").value("epm_abc"));
    }

    @Test
    void GET_사용자_간편결제_수단_목록_조회() throws Exception {
        EasyPayMethodResponse m = new EasyPayMethodResponse(
                "epm_001", "user_001", "tok_abc", "My Card", "ACTIVE");
        given(easyPayMethodService.listByUser("user_001")).willReturn(List.of(m));

        mockMvc.perform(get("/v1/easy-pay/methods").param("userId", "user_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].methodId").value("epm_001"));
    }

    @Test
    void DELETE_간편결제_수단_삭제_204_반환() throws Exception {
        mockMvc.perform(delete("/v1/easy-pay/methods/epm_001"))
                .andExpect(status().isNoContent());
    }
}
