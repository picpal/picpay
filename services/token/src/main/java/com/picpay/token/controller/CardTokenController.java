package com.picpay.token.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.service.CardTokenService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/tokens")
public class CardTokenController {

    private final CardTokenService cardTokenService;

    public CardTokenController(CardTokenService cardTokenService) {
        this.cardTokenService = cardTokenService;
    }

    @PostMapping("/card")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CardTokenResponse> issue(@Valid @RequestBody IssueCardTokenRequest request) {
        return ApiResponse.ok(cardTokenService.issue(request));
    }

    @GetMapping("/{tokenId}")
    public ApiResponse<CardTokenResponse> findByTokenId(@PathVariable String tokenId) {
        return ApiResponse.ok(cardTokenService.findByTokenId(tokenId));
    }
}
