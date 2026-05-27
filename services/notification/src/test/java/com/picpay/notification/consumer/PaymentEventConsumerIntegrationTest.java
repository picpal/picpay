package com.picpay.notification.consumer;

import com.picpay.notification.repository.ProcessedEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@SpringBootTest
@EmbeddedKafka(
        partitions = 1,
        topics = {"payment.completed", "payment.failed", "payment.cancelled"},
        brokerProperties = {"auto.create.topics.enable=true"}
)
@DirtiesContext
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.listener.concurrency=1",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "spring.jpa.properties.hibernate.default_schema=",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class PaymentEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @MockitoBean
    private ProcessedEventRepository processedEventRepository;

    @Test
    void paymentCompleted_isConsumedAndIdempotencyChecked() throws Exception {
        String eventId = "integ-test-event-001";
        String payload = "{\"tid\":\"TSVR01tid001\",\"status\":\"PAID\"}";

        when(processedEventRepository.insertIfNotExists(anyString(), anyString()))
                .thenReturn(1);

        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payment.completed", "TSVR01tid001", payload);
        record.headers().add(new RecordHeader("X-Event-Id",
                eventId.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(processedEventRepository, times(1))
                                .insertIfNotExists(eventId, "payment.completed")
                );
    }

    @Test
    void duplicateEvent_idempotencyRepositoryCalledBothTimes() throws Exception {
        String eventId = "integ-test-event-dup-001";
        String payload = "{\"tid\":\"TSVR01tid002\",\"status\":\"PAID\"}";

        // First call: new event (returns 1). Second call: duplicate (returns 0).
        when(processedEventRepository.insertIfNotExists(eventId, "payment.completed"))
                .thenReturn(1)
                .thenReturn(0);

        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payment.completed", "TSVR01tid002", payload);
        record.headers().add(new RecordHeader("X-Event-Id",
                eventId.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
        kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() ->
                        verify(processedEventRepository, times(2))
                                .insertIfNotExists(eventId, "payment.completed")
                );
    }
}
