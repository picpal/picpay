package com.picpay.billing.service;

import com.picpay.billing.domain.BillingHistory;
import com.picpay.billing.dto.BillingHistoryResponse;
import com.picpay.billing.repository.BillingHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillingHistoryServiceTest {

    @Mock BillingHistoryRepository billingHistoryRepository;
    @InjectMocks BillingHistoryService billingHistoryService;

    @Test
    void findByPlanId_returnsMappedResponses() {
        BillingHistory history = BillingHistory.success("BP-001", "TXN-001", 10000L);

        when(billingHistoryRepository.findByPlanIdOrderByCreatedAtDesc("BP-001"))
                .thenReturn(List.of(history));

        List<BillingHistoryResponse> result = billingHistoryService.findByPlanId("BP-001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).planId()).isEqualTo("BP-001");
        assertThat(result.get(0).tid()).isEqualTo("TXN-001");
        assertThat(result.get(0).status()).isEqualTo("SUCCESS");
        assertThat(result.get(0).amount()).isEqualTo(10000L);
    }
}
