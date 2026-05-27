package com.picpay.gateway.repository;

import com.picpay.gateway.domain.Merchant;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface MerchantRepository extends ReactiveCrudRepository<Merchant, Long> {
    Mono<Merchant> findByApiKey(String apiKey);
}
