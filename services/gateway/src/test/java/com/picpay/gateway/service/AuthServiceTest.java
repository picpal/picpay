package com.picpay.gateway.service;

import com.picpay.gateway.domain.Merchant;
import com.picpay.gateway.exception.UnauthorizedException;
import com.picpay.gateway.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock MerchantRepository merchantRepository;
    @Mock JwtService jwtService;

    AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(merchantRepository, jwtService);
    }

    @Test
    void shouldReturnTokenForActiveMerchant() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("mer_001");
        merchant.setApiKey("test-api-key-001");
        merchant.setStatus("ACTIVE");

        when(merchantRepository.findByApiKey("test-api-key-001")).thenReturn(Mono.just(merchant));
        when(jwtService.generate("mer_001")).thenReturn("mock-jwt-token");

        String token = authService.authenticate("test-api-key-001").block();
        assertThat(token).isEqualTo("mock-jwt-token");
    }

    @Test
    void shouldReturnErrorForInactiveMerchant() {
        Merchant merchant = new Merchant();
        merchant.setMerchantId("mer_002");
        merchant.setApiKey("inactive-key");
        merchant.setStatus("INACTIVE");

        when(merchantRepository.findByApiKey("inactive-key")).thenReturn(Mono.just(merchant));

        assertThatThrownBy(() -> authService.authenticate("inactive-key").block())
            .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void shouldReturnErrorForUnknownApiKey() {
        when(merchantRepository.findByApiKey("unknown-key")).thenReturn(Mono.empty());

        assertThatThrownBy(() -> authService.authenticate("unknown-key").block())
            .isInstanceOf(UnauthorizedException.class);
    }
}
