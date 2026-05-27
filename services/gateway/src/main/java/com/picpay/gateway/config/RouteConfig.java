package com.picpay.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {

    @Value("${services.payment.url}") private String paymentUrl;
    @Value("${services.billing.url}") private String billingUrl;
    @Value("${services.token.url}") private String tokenUrl;

    @Bean
    public RouteLocator routes(RouteLocatorBuilder builder) {
        return builder.routes()
            .route("payment", r -> r.path("/v1/payments/**")
                .filters(f -> f.circuitBreaker(c -> c.setName("paymentCB")
                    .setFallbackUri("forward:/fallback/payment")))
                .uri(paymentUrl))
            .route("billing", r -> r.path("/v1/billing/**")
                .filters(f -> f.circuitBreaker(c -> c.setName("billingCB")
                    .setFallbackUri("forward:/fallback/billing")))
                .uri(billingUrl))
            .route("token", r -> r.path("/v1/tokens/**", "/v1/easy-pay/**")
                .filters(f -> f.circuitBreaker(c -> c.setName("tokenCB")
                    .setFallbackUri("forward:/fallback/token")))
                .uri(tokenUrl))
            .build();
    }
}
