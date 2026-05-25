package com.picpay.token.dto;

import com.picpay.token.domain.EasyPayMethod;

public record EasyPayMethodResponse(
        String methodId,
        String userId,
        String tokenId,
        String methodName,
        String status
) {
    public static EasyPayMethodResponse from(EasyPayMethod m) {
        return new EasyPayMethodResponse(
                m.getMethodId(), m.getUserId(), m.getTokenId(),
                m.getMethodName(), m.getStatus().name());
    }
}
