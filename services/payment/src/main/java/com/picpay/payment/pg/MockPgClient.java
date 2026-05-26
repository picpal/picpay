package com.picpay.payment.pg;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class MockPgClient {

    private static final double APPROVAL_RATE = 0.95;

    public PgApprovalResult approve(String tid, Long amount) {
        if (ThreadLocalRandom.current().nextDouble() < APPROVAL_RATE) {
            return PgApprovalResult.success("PG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        return PgApprovalResult.failure("PG_DECLINED");
    }

    public PgApprovalResult cancel(String pgTid, Long cancelAmount) {
        return PgApprovalResult.success("PGC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
    }

    public record PgApprovalResult(boolean approved, String pgTid, String errorCode) {
        public static PgApprovalResult success(String pgTid) {
            return new PgApprovalResult(true, pgTid, null);
        }
        public static PgApprovalResult failure(String errorCode) {
            return new PgApprovalResult(false, null, errorCode);
        }
    }
}
