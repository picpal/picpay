package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingSchedulerTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock PaymentClient paymentClient;
    @InjectMocks BillingScheduler billingScheduler;

    @Test
    void execute_duePlan_callsPaymentAndSavesSuccessHistory() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(paymentClient).requestPayment(eq("mer_001"), any(), eq("tok_abc"), eq(10000L));
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
        verify(billingPlanRepository).save(any(BillingPlan.class));
    }

    @Test
    void execute_paymentFails_savesFailureHistory() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Payment service unavailable"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
    }

    @Test
    void execute_noDuePlans_doesNothing() {
        when(billingPlanRepository.findDuePlans(any(), any())).thenReturn(List.of());

        billingScheduler.execute();

        verifyNoInteractions(paymentClient);
    }
}
