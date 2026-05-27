package com.picpay.gateway.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);
    private static final String TRANSACTION_ID_HEADER = "X-Transaction-Id";

    @Override
    public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String tid = exchange.getRequest().getHeaders().getFirst(TRANSACTION_ID_HEADER);
        if (tid == null || tid.isBlank()) {
            tid = UUID.randomUUID().toString();
        }

        final String transactionId = tid;
        final long startTime = System.currentTimeMillis();
        final String method = exchange.getRequest().getMethod().name();
        final String path = exchange.getRequest().getPath().value();

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
            .header(TRANSACTION_ID_HEADER, transactionId)
            .build();
        ServerWebExchange mutatedExchange = exchange.mutate().request(mutatedRequest).build();

        mutatedExchange.getResponse().getHeaders().set(TRANSACTION_ID_HEADER, transactionId);

        return chain.filter(mutatedExchange)
            .doFinally(signalType -> {
                long duration = System.currentTimeMillis() - startTime;
                Integer statusCode = mutatedExchange.getResponse().getStatusCode() != null
                    ? mutatedExchange.getResponse().getStatusCode().value() : 0;
                log.info("tid={} method={} path={} status={} signal={} duration={}ms",
                    transactionId, method, path, statusCode, signalType, duration);
            });
    }
}
