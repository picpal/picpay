package com.picpay.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.gateway.service.RateLimitService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock
    private RateLimitService rateLimitService;

    private RateLimitFilter rateLimitFilter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        rateLimitFilter = new RateLimitFilter(rateLimitService, objectMapper);
    }

    @Test
    void filter_shouldSkipRateLimit_whenPathStartsWithV1Auth() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/auth/token").build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
        verify(rateLimitService, never()).isAllowed(anyString(), anyInt());
    }

    @Test
    void filter_shouldSkipRateLimit_whenNoMerchantIdHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123").build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
        verify(rateLimitService, never()).isAllowed(anyString(), anyInt());
    }

    @Test
    void filter_shouldCallChain_whenIsAllowedReturnsTrue() {
        when(rateLimitService.isAllowed(anyString(), anyInt())).thenReturn(Mono.just(true));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header("X-Merchant-Id", "mer_001")
                .build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void filter_shouldReturn429_whenIsAllowedReturnsFalse() {
        when(rateLimitService.isAllowed(anyString(), anyInt())).thenReturn(Mono.just(false));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header("X-Merchant-Id", "mer_001")
                .build()
        );
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void filter_shouldPassThrough_whenRedisIsDown() {
        when(rateLimitService.isAllowed(anyString(), anyInt()))
            .thenReturn(Mono.error(new RuntimeException("Redis connection refused")));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header("X-Merchant-Id", "mer_001")
                .build()
        );

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void filter_shouldIncludeRateLimitExceededCode_whenReturn429() throws Exception {
        when(rateLimitService.isAllowed(anyString(), anyInt())).thenReturn(Mono.just(false));

        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header("X-Merchant-Id", "mer_001")
                .build()
        );
        GatewayFilterChain chain = ex -> Mono.empty();

        rateLimitFilter.filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Verify that response body contains RATE_LIMIT_EXCEEDED
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("RATE_LIMIT_EXCEEDED");
    }
}
