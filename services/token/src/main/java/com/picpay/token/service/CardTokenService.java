package com.picpay.token.service;

import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.token.crypto.VaultService;
import com.picpay.token.domain.CardToken;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.repository.CardTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CardTokenService {

    private final VaultService vaultService;
    private final CardTokenRepository cardTokenRepository;

    public CardTokenService(VaultService vaultService,
                            CardTokenRepository cardTokenRepository) {
        this.vaultService = vaultService;
        this.cardTokenRepository = cardTokenRepository;
    }

    @Transactional
    public CardTokenResponse issue(IssueCardTokenRequest request) {
        // Derive non-sensitive data first, before encrypting plaintext card data
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
        CardToken token = cardTokenRepository.findByTokenId(tokenId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_NOT_FOUND));
        return CardTokenResponse.from(token);
    }
}
