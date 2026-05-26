package com.picpay.payment.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "partial_cancellations", schema = "payment")
public class PartialCancellation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "cancel_tid", nullable = false, unique = true)
    private String cancelTid;

    @Column(name = "cancel_amount", nullable = false)
    private Long cancelAmount;

    @Column(name = "remaining_amount", nullable = false)
    private Long remainingAmount;

    @Column(name = "reason")
    private String reason;

    @Column(name = "pg_cancel_tid")
    private String pgCancelTid;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected PartialCancellation() {}

    public static PartialCancellation create(Long paymentId, String cancelTid,
                                              Long cancelAmount, Long remainingAmount,
                                              String reason, String pgCancelTid) {
        PartialCancellation pc = new PartialCancellation();
        pc.paymentId = paymentId;
        pc.cancelTid = cancelTid;
        pc.cancelAmount = cancelAmount;
        pc.remainingAmount = remainingAmount;
        pc.reason = reason;
        pc.pgCancelTid = pgCancelTid;
        pc.status = "CANCELLED";
        return pc;
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getCancelTid() { return cancelTid; }
    public Long getCancelAmount() { return cancelAmount; }
    public Long getRemainingAmount() { return remainingAmount; }
    public String getReason() { return reason; }
    public String getPgCancelTid() { return pgCancelTid; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
