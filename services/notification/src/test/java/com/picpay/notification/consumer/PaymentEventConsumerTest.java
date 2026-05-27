package com.picpay.notification.consumer;

import com.picpay.notification.repository.ProcessedEventRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private Acknowledgment ack;

    private PaymentEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new PaymentEventConsumer(processedEventRepository);
    }

    private ConsumerRecord<String, String> buildRecord(String topic, String key, String value,
                                                        String eventId) {
        RecordHeaders headers = new RecordHeaders();
        if (eventId != null) {
            headers.add("X-Event-Id", eventId.getBytes(StandardCharsets.UTF_8));
        }
        return new ConsumerRecord<>(topic, 0, 100L, 0L, TimestampType.CREATE_TIME,
                key.length(), value.length(), key, value, headers, Optional.empty());
    }

    @Test
    void consume_newEvent_insertsAndAcknowledges() {
        ConsumerRecord<String, String> record = buildRecord(
                "payment.completed", "TSVR01tid001", "{\"status\":\"PAID\"}", "event-id-001");
        when(processedEventRepository.insertIfNotExists("event-id-001", "payment.completed")).thenReturn(1);

        consumer.consume(record, ack);

        verify(processedEventRepository).insertIfNotExists("event-id-001", "payment.completed");
        verify(ack).acknowledge();
    }

    @Test
    void consume_duplicateEvent_skipsAndAcknowledges() {
        ConsumerRecord<String, String> record = buildRecord(
                "payment.completed", "TSVR01tid001", "{\"status\":\"PAID\"}", "event-id-001");
        when(processedEventRepository.insertIfNotExists("event-id-001", "payment.completed")).thenReturn(0);

        consumer.consume(record, ack);

        verify(processedEventRepository).insertIfNotExists(eq("event-id-001"), eq("payment.completed"));
        verify(ack).acknowledge();
    }

    @Test
    void consume_noEventIdHeader_usesTopicPartitionOffset() {
        ConsumerRecord<String, String> record = buildRecord(
                "payment.failed", "TSVR01tid002", "{\"status\":\"FAILED\"}", null);
        String expectedEventId = "payment.failed-0-100";
        when(processedEventRepository.insertIfNotExists(expectedEventId, "payment.failed")).thenReturn(1);

        consumer.consume(record, ack);

        verify(processedEventRepository).insertIfNotExists(expectedEventId, "payment.failed");
        verify(ack).acknowledge();
    }
}
