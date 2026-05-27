package com.picpay.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class PaymentClient {

    private final RestClient paymentRestClient;

    public PaymentClient(RestClient paymentRestClient) {
        this.paymentRestClient = paymentRestClient;
    }

    public String requestPayment(String merchantId, String orderId, String tokenId, Long amount) {
        Map<String, Object> body = Map.of(
                "merchantId", merchantId,
                "orderId", orderId,
                "tokenId", tokenId,
                "amount", amount,
                "method", "CARD"
        );

        JsonNode response = paymentRestClient.post()
                .uri("/v1/payments")
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        if (response == null || !response.path("success").asBoolean()) {
            throw new IllegalStateException("Payment response missing or not successful");
        }
        String tid = response.path("data").path("tid").asText();
        if (tid.isBlank()) {
            throw new IllegalStateException("Payment response missing tid");
        }
        return tid;
    }
}
