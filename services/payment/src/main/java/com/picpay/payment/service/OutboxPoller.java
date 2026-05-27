package com.picpay.payment.service;

import com.picpay.payment.domain.OutboxEvent;
import com.picpay.payment.domain.OutboxStatus;
import com.picpay.payment.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPoller(OutboxEventRepository outboxEventRepository,
                        KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        List<OutboxEvent> events = outboxEventRepository.findPendingOrFailed();
        if (events.isEmpty()) return;

        for (OutboxEvent event : events) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        event.getTopic(), event.getAggregateId(), event.getPayload());
                record.headers().add("X-Event-Id",
                        String.valueOf(event.getId()).getBytes(StandardCharsets.UTF_8));
                kafkaTemplate.send(record).get();
                log.info("[Outbox] Published: topic={}, aggregateId={}, eventType={}",
                        event.getTopic(), event.getAggregateId(), event.getEventType());
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (Exception e) {
                log.error("[Outbox] Failed: id={}, error={}", event.getId(), e.getMessage());
                event.markFailed(e.getMessage());
                outboxEventRepository.save(event);
            }
        }
    }
}
