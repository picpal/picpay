package com.picpay.notification.consumer;

import com.picpay.notification.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final ProcessedEventRepository processedEventRepository;

    public PaymentEventConsumer(ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @KafkaListener(topics = {"payment.completed", "payment.failed", "payment.cancelled"},
                   groupId = "notification-group")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventId = extractEventId(record);
        String topic = record.topic();

        int inserted = processedEventRepository.insertIfNotExists(eventId, topic);
        if (inserted == 0) {
            log.info("[Notification] Duplicate skipped: eventId={}, topic={}", eventId, topic);
            ack.acknowledge();
            return;
        }

        log.info("[Notification] Processing: topic={}, key={}, eventId={}", topic, record.key(), eventId);
        log.info("[Notification] Payload: {}", record.value());
        ack.acknowledge();
    }

    private String extractEventId(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader("X-Event-Id");
        if (header != null) {
            return new String(header.value(), StandardCharsets.UTF_8);
        }
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }
}
