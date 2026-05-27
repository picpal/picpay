package com.picpay.billing.service;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BillingSchedulerTest {

    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock BillingRetryJobRepository billingRetryJobRepository;
    @Mock PaymentClient paymentClient;
    @Mock RedissonClient redissonClient;
    @InjectMocks BillingScheduler billingScheduler;

    private BillingPlan duePlan() {
        return BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
    }

    private RLock acquiredLock() throws InterruptedException {
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        return lock;
    }

    @Test
    void execute_lockAcquired_processesAndUnlocks() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(paymentClient).requestPayment(eq("mer_001"), any(), eq("tok_abc"), eq(10000L));
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
        verify(billingPlanRepository).save(any(BillingPlan.class));
        verify(lock).unlock();
    }

    @Test
    void execute_lockNotAcquired_skipsProcessing() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = mock(RLock.class);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(false);

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);

        billingScheduler.execute();

        verifyNoInteractions(paymentClient);
    }

    @Test
    void execute_paymentFails_savesFailureHistoryAndCreatesRetryJob() throws InterruptedException {
        BillingPlan plan = duePlan();
        RLock lock = acquiredLock();

        when(billingPlanRepository.findDuePlans(eq(BillingStatus.ACTIVE), any()))
                .thenReturn(List.of(plan));
        when(redissonClient.getLock("lock:billing:BP-001")).thenReturn(lock);
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Payment service unavailable"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        billingScheduler.execute();

        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
        verify(billingRetryJobRepository).save(argThat(j -> "BP-001".equals(j.getPlanId())));
        verify(lock).unlock();
    }

    @Test
    void execute_noDuePlans_doesNothing() {
        when(billingPlanRepository.findDuePlans(any(), any())).thenReturn(List.of());

        billingScheduler.execute();

        verifyNoInteractions(paymentClient, redissonClient);
    }
}
