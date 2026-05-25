package com.picpay.token.dto;

import com.picpay.token.domain.CardToken;

public record CardTokenResponse(
        String tokenId,
        String merchantId,
        String cardLastFour,
        String status
) {
    public static CardTokenResponse from(CardToken token) {
        return new CardTokenResponse(
                token.getTokenId(),
                token.getMerchantId(),
                token.getCardLastFour(),
                token.getStatus().name()
        );
    }
}
