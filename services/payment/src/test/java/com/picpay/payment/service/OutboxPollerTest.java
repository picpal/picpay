package com.picpay.payment.service;

import com.picpay.payment.domain.OutboxEvent;
import com.picpay.payment.domain.OutboxStatus;
import com.picpay.payment.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock private OutboxEventRepository outboxEventRepository;
    @SuppressWarnings("unchecked")
    @Mock private KafkaTemplate<String, String> kafkaTemplate;

    @InjectMocks private OutboxPoller outboxPoller;

    @Test
    void poll_emptyEvents_doesNothing() {
        when(outboxEventRepository.findPendingOrFailed()).thenReturn(List.of());

        outboxPoller.poll();

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poll_pendingEvent_publishesAndMarksPublished() {
        OutboxEvent event = OutboxEvent.create(
                "Payment", "TSVR01tid001", "payment.completed", "payment.completed", "{}");

        when(outboxEventRepository.findPendingOrFailed()).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxPoller.poll();

        verify(kafkaTemplate).send(any(ProducerRecord.class));
        verify(outboxEventRepository).save(argThat(e -> e.getStatus() == OutboxStatus.PUBLISHED));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poll_kafkaSendFails_marksEventFailed() {
        OutboxEvent event = OutboxEvent.create(
                "Payment", "TSVR01tid001", "payment.completed", "payment.completed", "{}");

        when(outboxEventRepository.findPendingOrFailed()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Kafka unavailable"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxPoller.poll();

        verify(outboxEventRepository).save(argThat(e -> e.getStatus() == OutboxStatus.FAILED));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void poll_eventExceedsMaxRetry_marksEventDead() {
        OutboxEvent event = OutboxEvent.create(
                "Payment", "TSVR01tid001", "payment.completed", "payment.completed", "{}");
        // simulate 4 prior failures (retryCount=4, status=FAILED — one more will tip to DEAD)
        for (int i = 0; i < 4; i++) {
            event.markFailed("previous failure");
        }

        when(outboxEventRepository.findPendingOrFailed()).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("Kafka unavailable"));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failed);
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxPoller.poll();

        verify(outboxEventRepository).save(argThat(e -> e.getStatus() == OutboxStatus.DEAD));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.DEAD);
    }
}
