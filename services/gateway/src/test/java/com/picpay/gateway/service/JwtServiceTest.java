package com.picpay.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    // Base64 encoding of "this is a 32-byte dev secret key!" (32 bytes)
    private static final String TEST_SECRET = "dGhpcyBpcyBhIDMyLWJ5dGUgZGV2IHNlY3JldCBrZXkh";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(TEST_SECRET, 3600000L);
    }

    @Test
    void generate_shouldReturnNonBlankToken() {
        String token = jwtService.generate("mer_001");

        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    void validate_shouldReturnClaimsWithCorrectMerchantId() {
        String merchantId = "mer_001";
        String token = jwtService.generate(merchantId);

        Claims claims = jwtService.validate(token);

        assertThat(claims).isNotNull();
        assertThat(claims.get("merchantId", String.class)).isEqualTo(merchantId);
        assertThat(claims.getSubject()).isEqualTo(merchantId);
    }

    @Test
    void validate_shouldThrowJwtExceptionForInvalidToken() {
        String invalidToken = "this.is.not.a.valid.jwt.token";

        assertThatThrownBy(() -> jwtService.validate(invalidToken))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void validate_shouldThrowJwtExceptionForTamperedToken() {
        String token = jwtService.generate("mer_001");
        String tamperedToken = token.substring(0, token.length() - 5) + "xxxxx";

        assertThatThrownBy(() -> jwtService.validate(tamperedToken))
            .isInstanceOf(JwtException.class);
    }
}
