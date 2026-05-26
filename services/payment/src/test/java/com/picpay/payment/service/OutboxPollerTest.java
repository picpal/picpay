package com.picpay.payment.service;

import com.picpay.payment.domain.OutboxEvent;
import com.picpay.payment.domain.OutboxStatus;
import com.picpay.payment.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPollerTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @InjectMocks
    private OutboxPoller outboxPoller;

    @Test
    void poll_publishesPendingEvents() {
        OutboxEvent event = OutboxEvent.create("Payment", "tid-001",
                "payment.completed", "payment.completed", "{\"tid\":\"tid-001\"}");
        when(outboxEventRepository.findPendingOrFailed()).thenReturn(List.of(event));
        when(outboxEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        outboxPoller.poll();

        verify(outboxEventRepository, times(1)).save(argThat(e ->
                e.getStatus() == OutboxStatus.PUBLISHED
        ));
    }

    @Test
    void poll_doesNothing_whenNoEvents() {
        when(outboxEventRepository.findPendingOrFailed()).thenReturn(List.of());

        outboxPoller.poll();

        verify(outboxEventRepository, never()).save(any());
    }
}
