package com.picpay.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.gateway.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private Claims claims;

    private JwtAuthFilter jwtAuthFilter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtAuthFilter = new JwtAuthFilter(jwtService, objectMapper);
    }

    @Test
    void filter_shouldSkipAuth_whenPathStartsWithV1Auth() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/auth/token").build()
        );
        AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            capturedExchange.set(ex);
            return Mono.empty();
        };

        jwtAuthFilter.filter(exchange, chain).block();

        assertThat(capturedExchange.get()).isNotNull();
        verify(jwtService, never()).validate(anyString());
    }

    @Test
    void filter_shouldReturn401_whenMissingAuthorizationHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123").build()
        );
        GatewayFilterChain chain = ex -> Mono.empty();

        jwtAuthFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void filter_shouldReturn401_whenAuthHeaderDoesNotStartWithBearer() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header(HttpHeaders.AUTHORIZATION, "Basic sometoken")
                .build()
        );
        GatewayFilterChain chain = ex -> Mono.empty();

        jwtAuthFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void filter_shouldReturn401_whenTokenIsInvalid() {
        when(jwtService.validate(anyString())).thenThrow(new JwtException("invalid token"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalidtoken")
                .build()
        );
        GatewayFilterChain chain = ex -> Mono.empty();

        jwtAuthFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void filter_shouldCallChainWithMerchantIdHeader_whenTokenIsValid() {
        when(claims.get("merchantId", String.class)).thenReturn("mer_001");
        when(jwtService.validate("validtoken")).thenReturn(claims);

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header(HttpHeaders.AUTHORIZATION, "Bearer validtoken")
                .build()
        );

        AtomicReference<ServerWebExchange> capturedExchange = new AtomicReference<>();
        GatewayFilterChain chain = ex -> {
            capturedExchange.set(ex);
            return Mono.empty();
        };

        jwtAuthFilter.filter(exchange, chain).block();

        assertThat(capturedExchange.get()).isNotNull();
        assertThat(capturedExchange.get().getRequest().getHeaders().getFirst("X-Merchant-Id"))
            .isEqualTo("mer_001");
    }
}
