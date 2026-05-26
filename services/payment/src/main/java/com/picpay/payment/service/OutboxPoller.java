package com.picpay.payment.service;

import com.picpay.payment.domain.OutboxEvent;
import com.picpay.payment.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;

    public OutboxPoller(OutboxEventRepository outboxEventRepository) {
        this.outboxEventRepository = outboxEventRepository;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void poll() {
        List<OutboxEvent> events = outboxEventRepository.findPendingOrFailed();
        if (events.isEmpty()) {
            return;
        }

        for (OutboxEvent event : events) {
            try {
                // Layer 4에서 KafkaTemplate.send()로 교체
                log.info("[Outbox] Publishing: topic={}, aggregateId={}, eventType={}",
                        event.getTopic(), event.getAggregateId(), event.getEventType());
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("[Outbox] Failed to publish event id={}: {}", event.getId(), e.getMessage());
                event.markFailed(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
