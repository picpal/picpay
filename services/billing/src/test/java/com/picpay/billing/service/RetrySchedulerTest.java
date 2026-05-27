package com.picpay.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingRetryJob;
import com.picpay.billing.domain.BillingStatus;
import com.picpay.billing.domain.RetryStatus;
import com.picpay.billing.repository.BillingHistoryRepository;
import com.picpay.billing.repository.BillingPlanRepository;
import com.picpay.billing.repository.BillingRetryJobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetrySchedulerTest {

    @Mock BillingRetryJobRepository billingRetryJobRepository;
    @Mock BillingPlanRepository billingPlanRepository;
    @Mock BillingHistoryRepository billingHistoryRepository;
    @Mock PaymentClient paymentClient;
    @Mock RedissonClient redissonClient;
    @SuppressWarnings("unchecked")
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks RetryScheduler retryScheduler;

    @Test
    void processRetryJob_success_marksJobDoneAndSavesSuccessHistory() throws Exception {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
        BillingRetryJob job = BillingRetryJob.create("BP-001", "previous error");

        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any())).thenReturn("TXN-001");
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        retryScheduler.processRetryJob(job);

        assertThat(job.getStatus()).isEqualTo(RetryStatus.DONE);
        verify(billingHistoryRepository).save(argThat(h -> "SUCCESS".equals(h.getStatus())));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("billing.executed"), eq("BP-001"), payloadCaptor.capture());
        JsonNode event = new ObjectMapper().readTree(payloadCaptor.getValue());
        assertThat(event.path("data").path("status").asText()).isEqualTo("SUCCESS");
    }

    @Test
    void processRetryJob_failureNotExhausted_schedulesNextRetry() {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
        BillingRetryJob job = BillingRetryJob.create("BP-001", "error");

        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("still failing"));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        retryScheduler.processRetryJob(job);

        assertThat(job.getStatus()).isEqualTo(RetryStatus.PENDING);
        assertThat(job.getRetryCount()).isEqualTo(1);
        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void processRetryJob_exhaustedRetries_marksDeadAndPausesPlan() throws Exception {
        BillingPlan plan = BillingPlan.of("BP-001", "mer_001", "tok_abc",
                10000L, "MONTHLY", LocalDateTime.now().minusHours(1));
        BillingRetryJob job = BillingRetryJob.create("BP-001", "error");
        job.prepareNextRetry("error1"); // retryCount=1
        job.prepareNextRetry("error2"); // retryCount=2

        when(billingPlanRepository.findByPlanId("BP-001")).thenReturn(Optional.of(plan));
        when(paymentClient.requestPayment(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("final failure"));
        when(billingPlanRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingHistoryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        retryScheduler.processRetryJob(job);

        assertThat(job.getStatus()).isEqualTo(RetryStatus.DEAD);
        assertThat(plan.getStatus()).isEqualTo(BillingStatus.PAUSED);
        verify(billingHistoryRepository).save(argThat(h -> "FAILED".equals(h.getStatus())));
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("billing.executed"), eq("BP-001"), payloadCaptor.capture());
        JsonNode event = new ObjectMapper().readTree(payloadCaptor.getValue());
        assertThat(event.path("data").path("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void processRetryJob_planNotFound_marksJobDead() {
        BillingRetryJob job = BillingRetryJob.create("BP-999", "previous error");

        when(billingPlanRepository.findByPlanId("BP-999")).thenReturn(Optional.empty());
        when(billingRetryJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        retryScheduler.processRetryJob(job);

        assertThat(job.getStatus()).isEqualTo(RetryStatus.DEAD);
        verifyNoInteractions(paymentClient, kafkaTemplate);
    }
}
