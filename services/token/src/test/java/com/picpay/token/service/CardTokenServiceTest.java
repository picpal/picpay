package com.picpay.token.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.token.crypto.VaultService;
import com.picpay.token.domain.CardToken;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.repository.CardTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CardTokenServiceTest {

    @Mock
    private VaultService vaultService;
    @Mock
    private CardTokenRepository cardTokenRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private CardTokenService cardTokenService;

    @BeforeEach
    void setUp() {
        cardTokenService = new CardTokenService(vaultService, cardTokenRepository, redisTemplate, new ObjectMapper());
    }

    @Test
    void 토큰_발급_시_카드번호와_유효기간_암호화() {
        given(vaultService.encrypt("4111111111111111")).willReturn("enc_number");
        given(vaultService.encrypt("12/28")).willReturn("enc_expiry");
        given(cardTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        IssueCardTokenRequest request = new IssueCardTokenRequest(
                "mer_001", "4111111111111111", "12/28", "123");

        CardTokenResponse response = cardTokenService.issue(request);

        assertThat(response.tokenId()).startsWith("tok_");
        assertThat(response.cardLastFour()).isEqualTo("1111");
        assertThat(response.merchantId()).isEqualTo("mer_001");
    }

    @Test
    void 토큰_발급_시_CVC는_저장되지_않음() {
        given(vaultService.encrypt(anyString())).willReturn("encrypted");
        given(cardTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        IssueCardTokenRequest request = new IssueCardTokenRequest(
                "mer_001", "4111111111111111", "12/28", "123");

        cardTokenService.issue(request);

        // cardNumber와 cardExpiry만 암호화, CVC("123")는 encrypt 호출 없음
        verify(vaultService, never()).encrypt("123");
    }

    @Test
    void 토큰_조회_시_캐시_히트면_DB_조회_안함() throws Exception {
        String tokenId = "tok_abc";
        String cached = """
                {"tokenId":"tok_abc","merchantId":"mer_001","cardLastFour":"1111","status":"ACTIVE"}
                """;
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get("token:tok_abc")).willReturn(cached);

        CardTokenResponse result = cardTokenService.findByTokenId(tokenId);

        assertThat(result.tokenId()).isEqualTo("tok_abc");
        verify(cardTokenRepository, never()).findByTokenId(anyString());
    }

    @Test
    void 토큰_조회_캐시_미스_시_DB_조회_후_캐시_저장() {
        String tokenId = "tok_abc";
        CardToken token = CardToken.create("tok_abc", "mer_001", "enc_num", "enc_exp", "1111");
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.get("token:tok_abc")).willReturn(null);
        given(cardTokenRepository.findByTokenId("tok_abc")).willReturn(java.util.Optional.of(token));

        CardTokenResponse result = cardTokenService.findByTokenId(tokenId);

        assertThat(result.cardLastFour()).isEqualTo("1111");
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }
}
