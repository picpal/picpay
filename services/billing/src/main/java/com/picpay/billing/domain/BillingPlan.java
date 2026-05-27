package com.picpay.billing.domain;

import com.picpay.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_plans", schema = "billing")
public class BillingPlan extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false, unique = true)
    private String planId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "cycle", nullable = false)
    private String cycle;

    @Column(name = "next_billing_at", nullable = false)
    private LocalDateTime nextBillingAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BillingStatus status = BillingStatus.ACTIVE;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    protected BillingPlan() {}

    public static BillingPlan of(String planId, String merchantId, String tokenId,
                                  Long amount, String cycle, LocalDateTime nextBillingAt) {
        BillingPlan p = new BillingPlan();
        p.planId = planId;
        p.merchantId = merchantId;
        p.tokenId = tokenId;
        p.amount = amount;
        p.cycle = cycle;
        p.nextBillingAt = nextBillingAt;
        return p;
    }

    public void cancel() {
        this.status = BillingStatus.CANCELLED;
    }

    public void pause() {
        this.status = BillingStatus.PAUSED;
    }

    public void advanceNextBillingAt() {
        if ("MONTHLY".equals(cycle)) {
            this.nextBillingAt = nextBillingAt.plusMonths(1);
        } else if ("WEEKLY".equals(cycle)) {
            this.nextBillingAt = nextBillingAt.plusWeeks(1);
        } else {
            this.nextBillingAt = nextBillingAt.plusDays(1);
        }
    }

    public Long getId() { return id; }
    public String getPlanId() { return planId; }
    public String getMerchantId() { return merchantId; }
    public String getTokenId() { return tokenId; }
    public Long getAmount() { return amount; }
    public String getCycle() { return cycle; }
    public LocalDateTime getNextBillingAt() { return nextBillingAt; }
    public BillingStatus getStatus() { return status; }
    public int getRetryCount() { return retryCount; }
}
