package com.picpay.payment.pg;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockPgClientTest {

    private final MockPgClient mockPgClient = new MockPgClient();

    @Test
    void approve_100times_approvalRateBetween80And100Percent() {
        int approved = 0;
        for (int i = 0; i < 100; i++) {
            MockPgClient.PgApprovalResult result = mockPgClient.approve("tid-" + i, 10000L);
            if (result.approved()) {
                approved++;
                assertThat(result.pgTid()).isNotBlank();
            } else {
                assertThat(result.errorCode()).isNotBlank();
            }
        }
        assertThat(approved).isBetween(80, 100);
    }

    @Test
    void cancel_alwaysSucceeds() {
        MockPgClient.PgApprovalResult result = mockPgClient.cancel("PG-ABCD1234", 5000L);
        assertThat(result.approved()).isTrue();
        assertThat(result.pgTid()).startsWith("PGC-");
    }
}
