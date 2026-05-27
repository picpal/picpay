package com.picpay.gateway.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.test.web.reactive.server.WebTestClient;

@WebFluxTest(FallbackController.class)
@ImportAutoConfiguration(exclude = {
    org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration.class,
    org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class,
    org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration.class
})
class FallbackControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @Test
    void fallback_shouldReturn503ForPayment() {
        webTestClient.get().uri("/fallback/payment")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE")
            .jsonPath("$.error.message").isEqualTo("payment service is temporarily unavailable");
    }

    @Test
    void fallback_shouldReturn503ForBilling() {
        webTestClient.get().uri("/fallback/billing")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE")
            .jsonPath("$.error.message").isEqualTo("billing service is temporarily unavailable");
    }

    @Test
    void fallback_shouldReturn503ForToken() {
        webTestClient.get().uri("/fallback/token")
            .exchange()
            .expectStatus().isEqualTo(503)
            .expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.error.code").isEqualTo("SERVICE_UNAVAILABLE")
            .jsonPath("$.error.message").isEqualTo("token service is temporarily unavailable");
    }
}
