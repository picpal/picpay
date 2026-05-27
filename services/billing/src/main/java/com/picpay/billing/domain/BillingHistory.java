package com.picpay.billing.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_histories", schema = "billing")
public class BillingHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "tid")
    private String tid;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected BillingHistory() {}

    public static BillingHistory success(String planId, String tid, Long amount) {
        BillingHistory h = new BillingHistory();
        h.planId = planId;
        h.tid = tid;
        h.amount = amount;
        h.status = "SUCCESS";
        return h;
    }

    public static BillingHistory failure(String planId, Long amount, String failureReason) {
        BillingHistory h = new BillingHistory();
        h.planId = planId;
        h.amount = amount;
        h.status = "FAILED";
        h.failureReason = failureReason;
        return h;
    }

    public Long getId() { return id; }
    public String getPlanId() { return planId; }
    public String getTid() { return tid; }
    public Long getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
