package com.picpay.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private ReactiveRedisTemplate<String, String> redisTemplate;

    @Mock
    private ReactiveZSetOperations<String, String> zSetOps;

    private RateLimitService rateLimitService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        rateLimitService = new RateLimitService(redisTemplate);
    }

    @Test
    void isAllowed_shouldReturnTrue_whenCountIsUnderLimit() {
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOps.removeRangeByScore(anyString(), any(Range.class))).thenReturn(Mono.just(1L));
        when(zSetOps.size(anyString())).thenReturn(Mono.just(50L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        Boolean result = rateLimitService.isAllowed("mer_001", 100).block();

        assertThat(result).isTrue();
    }

    @Test
    void isAllowed_shouldReturnFalse_whenCountExceedsLimit() {
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOps.removeRangeByScore(anyString(), any(Range.class))).thenReturn(Mono.just(1L));
        when(zSetOps.size(anyString())).thenReturn(Mono.just(101L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        Boolean result = rateLimitService.isAllowed("mer_001", 100).block();

        assertThat(result).isFalse();
    }

    @Test
    void isAllowed_shouldReturnTrue_whenCountEqualsLimit() {
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOps.removeRangeByScore(anyString(), any(Range.class))).thenReturn(Mono.just(0L));
        when(zSetOps.size(anyString())).thenReturn(Mono.just(100L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        Boolean result = rateLimitService.isAllowed("mer_001", 100).block();

        assertThat(result).isTrue();
    }

    @Test
    void isAllowed_shouldCallOperationsInCorrectOrder() {
        when(zSetOps.add(anyString(), anyString(), anyDouble())).thenReturn(Mono.just(true));
        when(zSetOps.removeRangeByScore(anyString(), any(Range.class))).thenReturn(Mono.just(1L));
        when(zSetOps.size(anyString())).thenReturn(Mono.just(50L));
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        rateLimitService.isAllowed("mer_001", 100).block();

        InOrder inOrder = inOrder(zSetOps, redisTemplate);
        inOrder.verify(zSetOps).add(eq("rate:mer_001"), anyString(), anyDouble());
        inOrder.verify(zSetOps).removeRangeByScore(eq("rate:mer_001"), any(Range.class));
        inOrder.verify(zSetOps).size(eq("rate:mer_001"));
        inOrder.verify(redisTemplate).expire(eq("rate:mer_001"), eq(Duration.ofMinutes(2)));
    }
}
