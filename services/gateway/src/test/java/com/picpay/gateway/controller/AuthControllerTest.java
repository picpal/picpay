package com.picpay.gateway.controller;

import com.picpay.gateway.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.when;

@WebFluxTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private AuthService authService;

    @Test
    void getToken_shouldReturn200WithToken_whenApiKeyIsValid() {
        String testToken = "eyJhbGciOiJIUzI1NiJ9.test.token";
        when(authService.authenticate("test-api-key-001")).thenReturn(Mono.just(testToken));

        webTestClient.post()
            .uri("/v1/auth/token")
            .header("X-Api-Key", "test-api-key-001")
            .exchange()
            .expectStatus().isOk()
            .expectBody()
            .jsonPath("$.success").isEqualTo(true)
            .jsonPath("$.data.token").isEqualTo(testToken);
    }

    @Test
    void getToken_shouldReturn401_whenApiKeyIsInvalid() {
        when(authService.authenticate("invalid-key"))
            .thenReturn(Mono.error(new RuntimeException("Invalid API key")));

        webTestClient.post()
            .uri("/v1/auth/token")
            .header("X-Api-Key", "invalid-key")
            .exchange()
            .expectStatus().isUnauthorized()
            .expectBody()
            .jsonPath("$.success").isEqualTo(false)
            .jsonPath("$.error.code").isEqualTo("UNAUTHORIZED");
    }
}
