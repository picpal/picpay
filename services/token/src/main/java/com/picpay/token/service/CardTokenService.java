package com.picpay.token.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.token.crypto.VaultService;
import com.picpay.token.domain.CardToken;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.repository.CardTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Service
public class CardTokenService {

    private static final Logger log = LoggerFactory.getLogger(CardTokenService.class);
    private static final String CACHE_PREFIX = "token:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final VaultService vaultService;
    private final CardTokenRepository cardTokenRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CardTokenService(VaultService vaultService,
                            CardTokenRepository cardTokenRepository,
                            StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper) {
        this.vaultService = vaultService;
        this.cardTokenRepository = cardTokenRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CardTokenResponse issue(IssueCardTokenRequest request) {
        String cardLastFour = request.cardNumber()
                .substring(request.cardNumber().length() - 4);
        String tokenId = "tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        String cardNumberEnc = vaultService.encrypt(request.cardNumber());
        String cardExpiryEnc = vaultService.encrypt(request.cardExpiry());
        // CVC는 암호화하지 않고 즉시 폐기 (PCI-DSS 3.2)

        CardToken saved = cardTokenRepository.save(
                CardToken.create(tokenId, request.merchantId(),
                        cardNumberEnc, cardExpiryEnc, cardLastFour));

        return CardTokenResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public CardTokenResponse findByTokenId(String tokenId) {
        String cacheKey = CACHE_PREFIX + tokenId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, CardTokenResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("Cache deserialization failed for key={}", cacheKey);
            }
        }

        CardToken token = cardTokenRepository.findByTokenId(tokenId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_NOT_FOUND));

        CardTokenResponse response = CardTokenResponse.from(token);
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(response), CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Cache serialization failed for key={}", cacheKey);
        }
        return response;
    }
}
