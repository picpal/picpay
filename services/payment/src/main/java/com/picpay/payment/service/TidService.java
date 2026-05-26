package com.picpay.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class TidService {

    private static final Logger log = LoggerFactory.getLogger(TidService.class);
    private static final String SERVICE_ID = "SVR01";
    private static final String SEQ_KEY_PREFIX = "tid:seq:";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter DATE_ONLY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate redisTemplate;

    public TidService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String generate() {
        LocalDateTime now = LocalDateTime.now();
        String datePart = now.format(DATE_FMT);
        String seqKey = SEQ_KEY_PREFIX + now.format(DATE_ONLY_FMT);
        String seq = generateSeq(seqKey);
        return "T" + SERVICE_ID + datePart + seq;
    }

    private String generateSeq(String seqKey) {
        try {
            Long seq = redisTemplate.opsForValue().increment(seqKey);
            if (seq == 1L) {
                redisTemplate.expire(seqKey, Duration.ofDays(2));
            }
            return String.format("%08d", seq);
        } catch (Exception e) {
            log.warn("Redis TID seq unavailable, falling back to UUID: {}", e.getMessage());
            return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        }
    }
}
