package com.picpay.payment.domain;

import com.picpay.common.entity.BaseEntity;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import jakarta.persistence.*;

@Entity
@Table(name = "payments", schema = "payment")
public class Payment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tid", nullable = false, unique = true)
    private String tid;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "method", nullable = false)
    private String method;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private PaymentStatus status = PaymentStatus.READY;

    @Column(name = "pg_tid")
    private String pgTid;

    @Column(name = "idempotency_key", unique = true)
    private String idempotencyKey;

    protected Payment() {}

    public static Payment create(String tid, String merchantId, String orderId,
                                  String tokenId, Long amount, String method,
                                  String idempotencyKey) {
        Payment p = new Payment();
        p.tid = tid;
        p.merchantId = merchantId;
        p.orderId = orderId;
        p.tokenId = tokenId;
        p.amount = amount;
        p.method = method;
        p.idempotencyKey = idempotencyKey;
        return p;
    }

    public void approve(String pgTid) {
        transitionTo(PaymentStatus.PAID);
        this.pgTid = pgTid;
    }

    public void fail() {
        transitionTo(PaymentStatus.FAILED);
    }

    public void cancel() {
        transitionTo(PaymentStatus.CANCELLED);
    }

    public void partialCancel() {
        transitionTo(PaymentStatus.PARTIAL_CANCELLED);
    }

    private void transitionTo(PaymentStatus next) {
        if (!this.status.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.INVALID_STATUS_TRANSITION);
        }
        this.status = next;
    }

    public Long getId() { return id; }
    public String getTid() { return tid; }
    public String getMerchantId() { return merchantId; }
    public String getOrderId() { return orderId; }
    public String getTokenId() { return tokenId; }
    public Long getAmount() { return amount; }
    public String getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getPgTid() { return pgTid; }
    public String getIdempotencyKey() { return idempotencyKey; }
}
