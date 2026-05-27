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
