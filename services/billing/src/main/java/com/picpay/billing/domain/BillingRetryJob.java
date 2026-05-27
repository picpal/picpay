package com.picpay.billing.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "billing_retry_jobs", schema = "billing")
public class BillingRetryJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_id", nullable = false)
    private String planId;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry = 3;

    @Column(name = "next_retry_at", nullable = false)
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error")
    private String lastError;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private RetryStatus status = RetryStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected BillingRetryJob() {}

    // First retry fires after 30 seconds (2^0 * 30)
    public static BillingRetryJob create(String planId, String lastError) {
        BillingRetryJob job = new BillingRetryJob();
        job.planId = planId;
        job.lastError = lastError;
        job.nextRetryAt = LocalDateTime.now().plusSeconds(30);
        return job;
    }

    public void markDone() {
        this.status = RetryStatus.DONE;
        this.updatedAt = LocalDateTime.now();
    }

    public void markDead() {
        this.status = RetryStatus.DEAD;
        this.updatedAt = LocalDateTime.now();
    }

    // Increments retryCount and schedules next: 2^retryCount * 30s
    // After 1st call: retryCount=1, delay=60s
    // After 2nd call: retryCount=2, delay=120s
    public void prepareNextRetry(String error) {
        this.retryCount++;
        this.lastError = error;
        long delaySeconds = (long) Math.pow(2, retryCount) * 30;
        this.nextRetryAt = LocalDateTime.now().plusSeconds(delaySeconds);
        this.updatedAt = LocalDateTime.now();
    }

    // Returns true if retryCount+1 would reach or exceed maxRetry
    public boolean isExhaustedAfterIncrement() {
        return (this.retryCount + 1) >= maxRetry;
    }

    public Long getId() { return id; }
    public String getPlanId() { return planId; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetry() { return maxRetry; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public String getLastError() { return lastError; }
    public RetryStatus getStatus() { return status; }
}
