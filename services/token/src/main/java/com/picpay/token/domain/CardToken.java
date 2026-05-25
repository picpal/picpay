package com.picpay.token.domain;

import com.picpay.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "card_tokens", schema = "token")
public class CardToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_id", nullable = false, unique = true)
    private String tokenId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "card_number_enc", nullable = false, columnDefinition = "TEXT")
    private String cardNumberEnc;

    @Column(name = "card_expiry_enc", nullable = false, columnDefinition = "TEXT")
    private String cardExpiryEnc;

    @Column(name = "card_last_four", nullable = false, length = 4)
    private String cardLastFour;

    @Column(name = "card_number_deleted_at")
    private LocalDateTime cardNumberDeletedAt;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private CardTokenStatus status = CardTokenStatus.ACTIVE;

    protected CardToken() {}

    public static CardToken create(String tokenId, String merchantId,
                                   String cardNumberEnc, String cardExpiryEnc,
                                   String cardLastFour) {
        CardToken t = new CardToken();
        t.tokenId = tokenId;
        t.merchantId = merchantId;
        t.cardNumberEnc = cardNumberEnc;
        t.cardExpiryEnc = cardExpiryEnc;
        t.cardLastFour = cardLastFour;
        return t;
    }

    public void softDelete() {
        this.status = CardTokenStatus.DELETED;
        this.cardNumberDeletedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getTokenId() { return tokenId; }
    public String getMerchantId() { return merchantId; }
    public String getCardNumberEnc() { return cardNumberEnc; }
    public String getCardExpiryEnc() { return cardExpiryEnc; }
    public String getCardLastFour() { return cardLastFour; }
    public CardTokenStatus getStatus() { return status; }
    public LocalDateTime getCardNumberDeletedAt() { return cardNumberDeletedAt; }
}
