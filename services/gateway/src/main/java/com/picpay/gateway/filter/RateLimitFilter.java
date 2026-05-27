package com.picpay.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.response.ApiResponse;
import com.picpay.gateway.service.RateLimitService;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimitService rateLimitService;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() { return 0; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/v1/auth/")) {
            return chain.filter(exchange);
        }

        String merchantId = exchange.getRequest().getHeaders().getFirst("X-Merchant-Id");
        if (merchantId == null) {
            return chain.filter(exchange);
        }

        return rateLimitService.isAllowed(merchantId, 100)
            .onErrorReturn(true)
            .flatMap(allowed -> {
                if (allowed) {
                    return chain.filter(exchange);
                }
                return writeError(exchange, HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMIT_EXCEEDED", "API rate limit exceeded");
            });
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        try {
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = objectMapper.writeValueAsBytes(ApiResponse.error(code, message));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("Failed to write rate limit error response", e);
            return exchange.getResponse().setComplete();
        }
    }
}
