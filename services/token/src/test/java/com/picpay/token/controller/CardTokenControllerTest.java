package com.picpay.token.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.common.exception.GlobalExceptionHandler;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.service.CardTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CardTokenController.class)
@Import(GlobalExceptionHandler.class)
class CardTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CardTokenService cardTokenService;

    @Test
    void POST_카드토큰_발급_201_반환() throws Exception {
        CardTokenResponse response = new CardTokenResponse(
                "tok_abc123", "mer_001", "1111", "ACTIVE");
        given(cardTokenService.issue(any())).willReturn(response);

        IssueCardTokenRequest request = new IssueCardTokenRequest(
                "mer_001", "4111111111111111", "12/28", "123");

        mockMvc.perform(post("/v1/tokens/card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenId").value("tok_abc123"))
                .andExpect(jsonPath("$.data.cardLastFour").value("1111"));
    }

    @Test
    void GET_존재하지_않는_토큰_조회_시_404() throws Exception {
        given(cardTokenService.findByTokenId("tok_unknown"))
                .willThrow(new BusinessException(ErrorCode.TOKEN_NOT_FOUND));

        mockMvc.perform(get("/v1/tokens/tok_unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOKEN_NOT_FOUND"));
    }
}
