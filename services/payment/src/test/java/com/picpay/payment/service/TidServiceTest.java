package com.picpay.payment.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TidServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    private TidService tidService;

    @BeforeEach
    void setUp() {
        tidService = new TidService(redisTemplate);
    }

    @Test
    void generate_returnsCorrectFormat_whenRedisAvailable() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        String tid = tidService.generate();

        assertThat(tid).startsWith("TSVR01");
        assertThat(tid).hasSize(28);
    }

    @Test
    void generate_fallbackToUuid_whenRedisUnavailable() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis connection refused"));

        String tid = tidService.generate();

        assertThat(tid).startsWith("TSVR01");
        assertThat(tid).hasSize(28);
    }

    @Test
    void generate_seqPadded8Digits_whenSeqIs1() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(anyString())).thenReturn(1L);

        String tid = tidService.generate();

        String seq = tid.substring(tid.length() - 8);
        assertThat(seq).isEqualTo("00000001");
    }
}
