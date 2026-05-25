package com.picpay.token.repository;

import com.picpay.token.domain.CardToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardTokenRepository extends JpaRepository<CardToken, Long> {
    Optional<CardToken> findByTokenId(String tokenId);
}
