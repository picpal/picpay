package com.picpay.gateway.service;

import org.springframework.data.domain.Range;
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
            .then(redisTemplate.opsForZSet().removeRangeByScore(key,
                Range.closed(0.0, (double) windowStart)))
            .then(redisTemplate.opsForZSet().size(key))
            .flatMap(count -> redisTemplate.expire(key, Duration.ofMinutes(2))
                .thenReturn(count <= limit));
    }
}
