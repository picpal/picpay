package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.dto.BillingPlanResponse;
import com.picpay.billing.dto.CreateBillingPlanRequest;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingPlanServiceTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @InjectMocks BillingPlanService billingPlanService;

    @Test
    void create_savesAndReturnsPlan() {
        CreateBillingPlanRequest request = new CreateBillingPlanRequest(
                "mer_001", "tok_abc", 10000L, "MONTHLY",
                LocalDateTime.of(2026, 6, 1, 0, 0));
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        BillingPlanResponse response = billingPlanService.create(request);

        assertThat(response.merchantId()).isEqualTo("mer_001");
        assertThat(response.amount()).isEqualTo(10000L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(billingPlanRepository).save(any(BillingPlan.class));
    }

    @Test
    void findByPlanId_notFound_throwsBusinessException() {
        when(billingPlanRepository.findByPlanId("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> billingPlanService.findByPlanId("unknown"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void cancel_activePlan_setsStatusCancelled() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().plusDays(30));
        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingPlanService.cancel("BP-001");

        assertThat(plan.getStatus()).isEqualTo(BillingStatus.CANCELLED);
    }
}
