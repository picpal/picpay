# Layer 6: API Gateway Implementation Plan

**Date:** 2026-05-27  
**Branch:** main  
**Module:** `services/gateway` (port 8080)

## Context

Spring Cloud Gateway service using WebFlux (reactive, NOT servlet). The gateway authenticates merchants via API Key → JWT exchange, rate-limits per merchant using Redis sliding window, routes to downstream services, adds Resilience4j circuit breakers, and logs all requests with transaction IDs.

### Key architectural constraints
- **Reactive everywhere**: WebFlux/R2DBC/ReactiveRedisTemplate — no JPA, no blocking RedisTemplate
- **Scan boundary**: `@SpringBootApplication(scanBasePackages = "com.picpay.gateway")` — NOT `"com.picpay"` (avoids servlet conflicts from common module)
- **Filter error handling**: Write 401/429 directly to `exchange.getResponse()` in GlobalFilter — `@RestControllerAdvice` does NOT catch GlobalFilter exceptions
- **Common module**: `spring-boot-starter-web` is excluded in gateway's build.gradle; `spring-boot-starter-data-jpa` is `compileOnly` in common so NOT on gateway runtime classpath
- **Merchant DB**: merchant.merchants table lives in payment service's V1__init.sql; gateway reads it via R2DBC with `flyway.enabled: false`
- **JWT library**: jjwt 0.12.x API — `Jwts.builder().subject()...signWith(key).compact()`, `Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()`
- **Filter order**: LoggingFilter=`Ordered.HIGHEST_PRECEDENCE`, JwtAuthFilter=-1, RateLimitFilter=0
- **Rate limit**: Redis Sorted Set sliding window, key=`rate:{merchantId}`, 60s window, 100 req/min, 2 min TTL
- **JWT secret env var**: `${JWT_SECRET:dGhpcyBpcyBhIDMyLWJ5dGUgZGV2IHNlY3JldCBrZXkh}` — never commit raw key bytes

### Existing files (do NOT delete)
- `services/gateway/build.gradle` — needs dependency additions
- `services/gateway/src/main/resources/application.yml` — needs full rewrite
- `services/gateway/src/main/java/com/picpay/gateway/GatewayApplication.java` — needs scanBasePackages fix

### merchant.merchants schema (from payment service V1__init.sql)
```sql
CREATE SCHEMA IF NOT EXISTS merchant;
CREATE TABLE merchant.merchants (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(64) UNIQUE NOT NULL,
    api_key VARCHAR(128) UNIQUE NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO merchant.merchants (merchant_id, api_key, status) VALUES ('mer_001', 'test-api-key-001', 'ACTIVE');
```

### ApiResponse (from common module)
```java
public record ApiResponse<T>(boolean success, T data, ErrorDetail error) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(true, data, null); }
    public static <T> ApiResponse<T> error(String code, String message) { return new ApiResponse<>(false, null, new ErrorDetail(code, message)); }
    public record ErrorDetail(String code, String message) {}
}
```

---

## S25: Gateway Routing Setup

**Files to create/modify:**
1. `services/gateway/build.gradle` — add R2DBC, JWT, circuit breaker dependencies
2. `services/gateway/src/main/resources/application.yml` — full rewrite with all config
3. `services/gateway/src/main/java/com/picpay/gateway/GatewayApplication.java` — fix scanBasePackages
4. `services/gateway/src/main/java/com/picpay/gateway/config/RouteConfig.java` — new file

**build.gradle** (complete replacement of dependencies block):
```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation(project(':common')) {
        exclude group: 'org.springframework.boot', module: 'spring-boot-starter-web'
    }
    implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis-reactive'
    implementation 'org.springframework.boot:spring-boot-starter-data-r2dbc'
    runtimeOnly 'org.postgresql:r2dbc-postgresql'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.6'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.6'
    implementation 'org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j'
}

dependencyManagement {
    imports {
        mavenBom "org.springframework.cloud:spring-cloud-dependencies:${springCloudVersion}"
    }
}
```

**application.yml** (full rewrite):
```yaml
spring:
  application:
    name: gateway-service
  r2dbc:
    url: r2dbc:postgresql://localhost:5432/picpay
    username: picpay
    password: picpay
  flyway:
    enabled: false
  data:
    redis:
      host: localhost
      port: 6379

server:
  port: 8080

jwt:
  secret: ${JWT_SECRET:dGhpcyBpcyBhIDMyLWJ5dGUgZGV2IHNlY3JldCBrZXkh}
  expiration-ms: 3600000

services:
  payment:
    url: http://localhost:8081
  billing:
    url: http://localhost:8082
  token:
    url: http://localhost:8083

resilience4j:
  circuitbreaker:
    instances:
      paymentCB:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
      billingCB:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3
      tokenCB:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
        permitted-number-of-calls-in-half-open-state: 3

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

**GatewayApplication.java** (change scanBasePackages):
```java
package com.picpay.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.picpay.gateway")
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

**RouteConfig.java** (with circuit breakers):
```java
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
```

**Tests** (`src/test/java/com/picpay/gateway/config/RouteConfigTest.java`):
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` or simply verify bean loads
- Verify RouteLocator bean is created with 3 routes (payment, billing, token)
- Alternatively use `@WebFluxTest` with mock downstream servers

**Acceptance criteria:**
- Application starts without errors
- `GET /actuator/health` returns 200
- Routes bean has 3 configured routes
- `build.gradle` has all required dependencies

---

## S26: API Key → JWT Authentication

**Files to create:**
1. `services/gateway/src/main/java/com/picpay/gateway/domain/Merchant.java`
2. `services/gateway/src/main/java/com/picpay/gateway/repository/MerchantRepository.java`
3. `services/gateway/src/main/java/com/picpay/gateway/service/JwtService.java`
4. `services/gateway/src/main/java/com/picpay/gateway/service/AuthService.java`
5. `services/gateway/src/main/java/com/picpay/gateway/controller/AuthController.java`
6. `services/gateway/src/main/java/com/picpay/gateway/filter/JwtAuthFilter.java`

**Merchant.java** (R2DBC entity, Spring Data R2DBC 3.x requires explicit schema in @Table):
```java
package com.picpay.gateway.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(schema = "merchant", name = "merchants")
public class Merchant {
    @Id private Long id;
    @Column("merchant_id") private String merchantId;
    @Column("api_key") private String apiKey;
    @Column("status") private String status;

    // getters/setters or use Lombok @Getter @Setter
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
```

**MerchantRepository.java**:
```java
package com.picpay.gateway.repository;

import com.picpay.gateway.domain.Merchant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface MerchantRepository extends ReactiveCrudRepository<Merchant, Long> {
    Mono<Merchant> findByApiKey(String apiKey);
}
```

**JwtService.java** (jjwt 0.12.x API):
```java
package com.picpay.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

@Component
public class JwtService {

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(@Value("${jwt.secret}") String secret,
                      @Value("${jwt.expiration-ms:3600000}") long expirationMs) {
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
    }

    public String generate(String merchantId) {
        return Jwts.builder()
            .subject(merchantId)
            .claim("merchantId", merchantId)
            .issuedAt(new Date())
            .expiration(new Date(System.currentTimeMillis() + expirationMs))
            .signWith(key)
            .compact();
    }

    public Claims validate(String token) {
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
```

**AuthService.java**:
```java
package com.picpay.gateway.service;

import com.picpay.gateway.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class AuthService {

    private final MerchantRepository merchantRepository;
    private final JwtService jwtService;

    public AuthService(MerchantRepository merchantRepository, JwtService jwtService) {
        this.merchantRepository = merchantRepository;
        this.jwtService = jwtService;
    }

    public Mono<String> authenticate(String apiKey) {
        return merchantRepository.findByApiKey(apiKey)
            .filter(m -> "ACTIVE".equals(m.getStatus()))
            .map(m -> jwtService.generate(m.getMerchantId()))
            .switchIfEmpty(Mono.error(new RuntimeException("Invalid API key")));
    }
}
```

**AuthController.java** (`POST /v1/auth/token`):
```java
package com.picpay.gateway.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.gateway.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/token")
    public Mono<ResponseEntity<ApiResponse<TokenResponse>>> getToken(
            @RequestHeader("X-Api-Key") String apiKey) {
        return authService.authenticate(apiKey)
            .map(token -> ResponseEntity.ok(ApiResponse.ok(new TokenResponse(token))))
            .onErrorReturn(ResponseEntity.status(401)
                .body(ApiResponse.error("UNAUTHORIZED", "Authentication failed")));
    }

    public record TokenResponse(String token) {}
}
```

**JwtAuthFilter.java** (GlobalFilter, order=-1, skips `/v1/auth/**`):
```java
package com.picpay.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.response.ApiResponse;
import com.picpay.gateway.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public JwtAuthFilter(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() { return -1; }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/v1/auth/")) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication failed");
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtService.validate(token);
            String merchantId = claims.get("merchantId", String.class);
            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header("X-Merchant-Id", merchantId)
                .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        } catch (JwtException e) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication failed");
        }
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        try {
            exchange.getResponse().setStatusCode(status);
            exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
            byte[] bytes = objectMapper.writeValueAsBytes(ApiResponse.error(code, message));
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return exchange.getResponse().setComplete();
        }
    }
}
```

**Tests:**
- `JwtServiceTest`: test `generate()` produces valid JWT, `validate()` returns correct claims, `validate()` throws on invalid token
- `JwtAuthFilterTest` (MockServerWebExchange): test skip for `/v1/auth/token`, test 401 on missing header, test 401 on invalid token, test passes and adds `X-Merchant-Id` on valid token
- `AuthControllerTest` (`@WebFluxTest(AuthController.class)`): test 200 + token on valid API key, test 401 on invalid API key

**Acceptance criteria:**
- `POST /v1/auth/token` with `X-Api-Key: test-api-key-001` returns JWT token
- `GET /v1/payments/*` without token returns 401
- `GET /v1/payments/*` with valid JWT returns proxied response (or 503 if payment service down)
- `X-Merchant-Id` header set on downstream request

---

## S27: Redis Sliding Window Rate Limiting

**Files to create:**
1. `services/gateway/src/main/java/com/picpay/gateway/service/RateLimitService.java`
2. `services/gateway/src/main/java/com/picpay/gateway/filter/RateLimitFilter.java`

**RateLimitService.java** (non-atomic sliding window per PRD spec):
```java
package com.picpay.gateway.service;

import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.UUID;

@Service
public class RateLimitService {

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public RateLimitService(ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Boolean> isAllowed(String merchantId, int limit) {
        String key = "rate:" + merchantId;
        long now = System.currentTimeMillis();
        long windowStart = now - 60_000;

        return redisTemplate.opsForZSet().add(key, UUID.randomUUID().toString(), now)
            .then(redisTemplate.opsForZSet().removeRangeByScore(key, 0, windowStart))
            .then(redisTemplate.opsForZSet().zCard(key))
            .flatMap(count -> redisTemplate.expire(key, Duration.ofMinutes(2))
                .thenReturn(count <= limit));
    }
}
```

**RateLimitFilter.java** (GlobalFilter, order=0, skips `/v1/auth/**`):
```java
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
            return exchange.getResponse().setComplete();
        }
    }
}
```

**Tests** (`RateLimitFilterTest` with MockServerWebExchange and mocked RateLimitService):
- Test skip for `/v1/auth/token`
- Test skip when no `X-Merchant-Id` header (passes through)
- Test 429 when `isAllowed` returns false
- Test passes through when `isAllowed` returns true

**Acceptance criteria:**
- Rate limit filter runs after JWT filter (order=0 > order=-1)
- 429 with `RATE_LIMIT_EXCEEDED` error code when limit exceeded
- Passes through when under limit
- `/v1/auth/**` paths bypass rate limiting

---

## S28: Circuit Breaker + Fallback Controller

**Note:** Circuit breaker dependencies and RouteConfig are already done in S25. This task only adds the FallbackController.

**Files to create:**
1. `services/gateway/src/main/java/com/picpay/gateway/controller/FallbackController.java`

**FallbackController.java**:
```java
package com.picpay.gateway.controller;

import com.picpay.common.response.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
public class FallbackController {

    @RequestMapping("/fallback/{service}")
    public Mono<ResponseEntity<ApiResponse<Void>>> fallback(@PathVariable String service) {
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error("SERVICE_UNAVAILABLE",
                service + " service is temporarily unavailable")));
    }
}
```

**Tests** (`FallbackControllerTest` with `@WebFluxTest(FallbackController.class)`):
- `GET /fallback/payment` returns 503 with `SERVICE_UNAVAILABLE` error code and message "payment service is temporarily unavailable"
- `GET /fallback/billing` returns 503 with correct message
- `GET /fallback/token` returns 503 with correct message

**Acceptance criteria:**
- `/fallback/{service}` returns 503 with `SERVICE_UNAVAILABLE` error code
- Circuit breaker config present in application.yml for paymentCB, billingCB, tokenCB
- Routes configured with correct fallback URIs

---

## S29: Logging Filter

**Files to create:**
1. `services/gateway/src/main/java/com/picpay/gateway/filter/LoggingFilter.java`

**LoggingFilter.java** (GlobalFilter, order=HIGHEST_PRECEDENCE, generates/propagates X-Transaction-Id):
```java
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

        return chain.filter(mutatedExchange)
            .doFinally(signalType -> {
                long duration = System.currentTimeMillis() - startTime;
                Integer statusCode = mutatedExchange.getResponse().getStatusCode() != null
                    ? mutatedExchange.getResponse().getStatusCode().value() : 0;
                log.info("tid={} method={} path={} status={} duration={}ms",
                    transactionId, method, path, statusCode, duration);
            });
    }
}
```

**Tests** (`LoggingFilterTest` with MockServerWebExchange):
- Test generates UUID when `X-Transaction-Id` header is absent
- Test propagates existing `X-Transaction-Id` when present
- Test `X-Transaction-Id` is set on downstream request
- Test filter order is `Ordered.HIGHEST_PRECEDENCE`

**Acceptance criteria:**
- Every request logged with tid, method, path, status, duration
- `X-Transaction-Id` propagated if present, generated if absent
- Filter runs before all other filters (HIGHEST_PRECEDENCE)
