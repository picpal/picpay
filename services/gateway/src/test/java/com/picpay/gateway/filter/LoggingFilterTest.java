package com.picpay.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingFilterTest {

    LoggingFilter filter;

    @BeforeEach
    void setUp() {
        filter = new LoggingFilter();
    }

    @Test
    void shouldGenerateTransactionIdWhenAbsent() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123").build()
        );

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> { captured.set(ex); return Mono.empty(); };

        filter.filter(exchange, chain).block();

        String tid = captured.get().getRequest().getHeaders().getFirst("X-Transaction-Id");
        assertThat(tid).isNotNull().isNotBlank();
    }

    @Test
    void shouldPropagateExistingTransactionId() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123")
                .header("X-Transaction-Id", "existing-tid-123")
                .build()
        );

        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = ex -> { captured.set(ex); return Mono.empty(); };

        filter.filter(exchange, chain).block();

        String tid = captured.get().getRequest().getHeaders().getFirst("X-Transaction-Id");
        assertThat(tid).isEqualTo("existing-tid-123");
    }

    @Test
    void shouldHaveHighestPrecedenceOrder() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    void shouldAlwaysCallChain() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123").build()
        );

        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);
        GatewayFilterChain chain = ex -> { chainCalled.set(true); return Mono.empty(); };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    void shouldPropagateChainErrors() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
            MockServerHttpRequest.get("/v1/payments/123").build()
        );
        GatewayFilterChain chain = ex -> Mono.error(new RuntimeException("downstream failure"));

        Mono<Void> result = filter.filter(exchange, chain);

        assertThatThrownBy(result::block)
            .isInstanceOf(RuntimeException.class)
            .hasMessage("downstream failure");
    }
}
