# Layer 4: 이벤트 레이어 (Kafka + Notification) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layer 3 Outbox를 실제 Kafka와 연결하고, Notification Service에서 at-least-once + 멱등성 소비 구현 — 완료 후 결제 승인 → Outbox → Kafka → Notification 알림 E2E 동작

**Architecture:**
- Payment Service: `KafkaTemplate.send()` 로 OutboxPoller 실제 발행, 토픽 4개 자동 생성
- Notification Service: `@KafkaListener` 3개 토픽 구독, `enable.auto.commit=false` + 수동 ack
- 멱등성: `X-Event-Id` Kafka 헤더 → `notification.processed_events` INSERT ON CONFLICT DO NOTHING

**Tech Stack:** Java 21, Spring Boot 3.4.0, Spring Kafka, Kafka 7.5 (Confluent CP), `@KafkaListener`, `Acknowledgment`, Spring Data JPA, PostgreSQL 16 (schema: notification)

---

## 파일 구조

```
services/payment/
├── build.gradle                                              (수정: spring-kafka 추가)
├── src/main/resources/application.yml                       (수정: kafka producer 설정)
└── src/main/java/com/picpay/payment/
    ├── config/
    │   └── KafkaConfig.java                                 (생성: NewTopic 4개)
    └── service/
        └── OutboxPoller.java                                (수정: KafkaTemplate.send() 연동)

services/payment/src/test/java/com/picpay/payment/
    └── service/
        └── OutboxPollerTest.java                            (생성: Mockito 단위 테스트)

services/notification/
├── build.gradle                                              (수정: spring-kafka 추가)
├── src/main/resources/application.yml                       (수정: kafka consumer 설정)
└── src/main/java/com/picpay/notification/
    ├── domain/
    │   └── ProcessedEvent.java                              (생성: notification.processed_events 엔티티)
    ├── repository/
    │   └── ProcessedEventRepository.java                    (생성: insertIfNotExists 네이티브 쿼리)
    └── consumer/
        └── PaymentEventConsumer.java                        (생성: @KafkaListener + Acknowledgment + 멱등성)

services/notification/src/test/java/com/picpay/notification/
    └── consumer/
        └── PaymentEventConsumerTest.java                    (생성: Mockito 단위 테스트)
```

---

## Task 1: Kafka Producer 설정 (S15)

**Files:**
- Modify: `services/payment/build.gradle`
- Modify: `services/payment/src/main/resources/application.yml`
- Create: `services/payment/src/main/java/com/picpay/payment/config/KafkaConfig.java`

- [ ] **Step 1: `services/payment/build.gradle`에 spring-kafka 추가**

```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.kafka:spring-kafka'
    implementation 'org.flywaydb:flyway-core'
    implementation 'org.flywaydb:flyway-database-postgresql'
    runtimeOnly 'org.postgresql:postgresql'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
}
```

- [ ] **Step 2: `services/payment/src/main/resources/application.yml`에 Kafka producer 설정 추가**

기존 파일에서 `spring:` 블록 내부에 `kafka:` 섹션 추가:

```yaml
spring:
  application:
    name: payment-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/picpay?currentSchema=payment
    username: picpay
    password: picpay
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: payment
  flyway:
    enabled: true
    locations: classpath:db/migration
    schemas: payment
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      acks: all
      enable-idempotence: true
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3: `KafkaConfig.java` 작성 (토픽 4개 자동 생성)**

`services/payment/src/main/java/com/picpay/payment/config/KafkaConfig.java`:
```java
package com.picpay.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic paymentCompleted() {
        return TopicBuilder.name("payment.completed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailed() {
        return TopicBuilder.name("payment.failed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentCancelled() {
        return TopicBuilder.name("payment.cancelled").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic billingExecuted() {
        return TopicBuilder.name("billing.executed").partitions(3).replicas(1).build();
    }
}
```

- [ ] **Step 4: 빌드 확인**

```bash
./gradlew :services:payment:build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add services/payment/build.gradle services/payment/src/main/resources/application.yml services/payment/src/main/java/com/picpay/payment/config/KafkaConfig.java
git commit -m "feat(payment): add Kafka producer config and topic definitions (Layer 4)"
```

---

## Task 2: Outbox Poller → KafkaTemplate.send() (S16)

**Files:**
- Modify: `services/payment/src/main/java/com/picpay/payment/service/OutboxPoller.java`
- Create: `services/payment/src/test/java/com/picpay/payment/service/OutboxPollerTest.java`

- [ ] **Step 1: `OutboxPoller.java` 수정 — KafkaTemplate.send() 연동**

`services/payment/src/main/java/com/picpay/payment/service/OutboxPoller.java`:
```java
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
```

- [ ] **Step 2: `OutboxPollerTest.java` 작성**

`services/payment/src/test/java/com/picpay/payment/service/OutboxPollerTest.java`:
```java
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
        // retryCount를 maxRetry(5) 직전까지 올려놓기 (4회 실패 시뮬레이션)
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
```

- [ ] **Step 3: 테스트 실행**

```bash
./gradlew :services:payment:test --tests "com.picpay.payment.service.OutboxPollerTest" -i
```

Expected: 4개 테스트 PASS

- [ ] **Step 4: 전체 payment 테스트 실행**

```bash
./gradlew :services:payment:test -i
```

Expected: BUILD SUCCESSFUL, 전체 테스트 PASS

- [ ] **Step 5: 커밋**

```bash
git add services/payment/src/main/java/com/picpay/payment/service/OutboxPoller.java
git add services/payment/src/test/java/com/picpay/payment/service/OutboxPollerTest.java
git commit -m "feat(payment): connect OutboxPoller to KafkaTemplate.send() with X-Event-Id header"
```

---

## Task 3: Notification Service @KafkaListener 기반 (S17)

**Files:**
- Modify: `services/notification/build.gradle`
- Modify: `services/notification/src/main/resources/application.yml`
- Create: `services/notification/src/main/java/com/picpay/notification/consumer/PaymentEventConsumer.java`

- [ ] **Step 1: `services/notification/build.gradle`에 spring-kafka 추가**

```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.kafka:spring-kafka'
    runtimeOnly 'org.postgresql:postgresql'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
}
```

- [ ] **Step 2: `services/notification/src/main/resources/application.yml` 수정 — Kafka consumer 설정 추가**

```yaml
spring:
  application:
    name: notification-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/picpay?currentSchema=notification
    username: picpay
    password: picpay
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: notification
  flyway:
    enabled: false
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: notification-group
      auto-offset-reset: earliest
      enable-auto-commit: false
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    listener:
      ack-mode: manual_immediate
      concurrency: 3

server:
  port: 8084

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3: `PaymentEventConsumer.java` 작성 (로그만, 멱등성 없음)**

`services/notification/src/main/java/com/picpay/notification/consumer/PaymentEventConsumer.java`:
```java
package com.picpay.notification.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    @KafkaListener(topics = {"payment.completed", "payment.failed", "payment.cancelled"},
                   groupId = "notification-group")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.info("[Notification] Received: topic={}, key={}, partition={}, offset={}",
                record.topic(), record.key(), record.partition(), record.offset());
        log.info("[Notification] Payload: {}", record.value());
        ack.acknowledge();
    }
}
```

- [ ] **Step 4: 빌드 확인**

```bash
./gradlew :services:notification:build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add services/notification/build.gradle services/notification/src/main/resources/application.yml
git add services/notification/src/main/java/com/picpay/notification/consumer/PaymentEventConsumer.java
git commit -m "feat(notification): add Kafka consumer @KafkaListener for payment topics"
```

---

## Task 4: 수동 커밋 + Consumer 멱등성 (S18)

**Files:**
- Create: `services/notification/src/main/java/com/picpay/notification/domain/ProcessedEvent.java`
- Create: `services/notification/src/main/java/com/picpay/notification/repository/ProcessedEventRepository.java`
- Modify: `services/notification/src/main/java/com/picpay/notification/consumer/PaymentEventConsumer.java`
- Create: `services/notification/src/test/java/com/picpay/notification/consumer/PaymentEventConsumerTest.java`

- [ ] **Step 1: `ProcessedEvent.java` 엔티티 작성**

`services/notification/src/main/java/com/picpay/notification/domain/ProcessedEvent.java`:
```java
package com.picpay.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "processed_events", schema = "notification")
public class ProcessedEvent {

    @Id
    @Column(name = "event_id")
    private String eventId;

    @Column(name = "topic", nullable = false)
    private String topic;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    protected ProcessedEvent() {}

    public static ProcessedEvent of(String eventId, String topic) {
        ProcessedEvent e = new ProcessedEvent();
        e.eventId = eventId;
        e.topic = topic;
        e.processedAt = LocalDateTime.now();
        return e;
    }

    public String getEventId() { return eventId; }
    public String getTopic() { return topic; }
    public LocalDateTime getProcessedAt() { return processedAt; }
}
```

- [ ] **Step 2: `ProcessedEventRepository.java` 작성**

`services/notification/src/main/java/com/picpay/notification/repository/ProcessedEventRepository.java`:
```java
package com.picpay.notification.repository;

import com.picpay.notification.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO notification.processed_events (event_id, topic, processed_at)
            VALUES (:eventId, :topic, NOW())
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfNotExists(@Param("eventId") String eventId, @Param("topic") String topic);
}
```

- [ ] **Step 3: `PaymentEventConsumer.java` 수정 — 멱등성 처리 추가**

`services/notification/src/main/java/com/picpay/notification/consumer/PaymentEventConsumer.java`:
```java
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
```

- [ ] **Step 4: `PaymentEventConsumerTest.java` 작성**

테스트 디렉토리 생성 확인:
```bash
mkdir -p services/notification/src/test/java/com/picpay/notification/consumer
```

`services/notification/src/test/java/com/picpay/notification/consumer/PaymentEventConsumerTest.java`:
```java
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
```

- [ ] **Step 5: 테스트 실행**

```bash
./gradlew :services:notification:test --tests "com.picpay.notification.consumer.PaymentEventConsumerTest" -i
```

Expected: 3개 테스트 PASS

- [ ] **Step 6: notification 전체 빌드**

```bash
./gradlew :services:notification:build -x test
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: 커밋**

```bash
git add services/notification/src/main/java/com/picpay/notification/
git add services/notification/src/test/java/com/picpay/notification/
git commit -m "feat(notification): add consumer idempotency with processed_events INSERT ON CONFLICT DO NOTHING"
```

---

## Task 5: E2E 통합 검증 (S19)

**Files:**
- Create: `services/notification/src/test/java/com/picpay/notification/consumer/PaymentEventConsumerIntegrationTest.java`

**완료 기준:** 결제 1건 후 Notification 로그 확인, Consumer Lag = 0

### 5-A: 자동화 통합 테스트 (EmbeddedKafka)

- [ ] **Step 1: 통합 테스트 작성 (EmbeddedKafka)**

`services/notification/src/test/java/com/picpay/notification/consumer/PaymentEventConsumerIntegrationTest.java`:
```java
package com.picpay.notification.consumer;

import com.picpay.notification.repository.ProcessedEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@EmbeddedKafka(partitions = 3, topics = {"payment.completed", "payment.failed", "payment.cancelled"})
@DirtiesContext
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.properties.hibernate.default_schema="
})
class PaymentEventConsumerIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Test
    void paymentCompleted_isConsumedExactlyOnce() throws Exception {
        String eventId = "test-event-001";
        String payload = "{\"tid\":\"TSVR01tid001\",\"status\":\"PAID\"}";

        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payment.completed", "TSVR01tid001", payload);
        record.headers().add(new RecordHeader("X-Event-Id",
                eventId.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record).get();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        processedEventRepository.existsById(eventId)).isTrue());
    }

    @Test
    void duplicateEvent_processedOnlyOnce() throws Exception {
        String eventId = "test-event-dup-001";
        String payload = "{\"tid\":\"TSVR01tid002\",\"status\":\"PAID\"}";

        ProducerRecord<String, String> record = new ProducerRecord<>(
                "payment.completed", "TSVR01tid002", payload);
        record.headers().add(new RecordHeader("X-Event-Id",
                eventId.getBytes(StandardCharsets.UTF_8)));

        kafkaTemplate.send(record).get();
        kafkaTemplate.send(record).get();

        await().atMost(10, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(
                        processedEventRepository.existsById(eventId)).isTrue());

        long count = processedEventRepository.count();
        assertThat(count).isEqualTo(1L);
    }
}
```

> **주의:** H2가 PostgreSQL `ON CONFLICT DO NOTHING`을 지원하지 않을 경우 이 통합 테스트는 `-x integrationTest` 플래그로 CI에서 제외하고 5-B 수동 검증으로 대체한다.

- [ ] **Step 2: `services/notification/build.gradle`에 Awaitility + H2 테스트 의존성 추가**

```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.kafka:spring-kafka'
    runtimeOnly 'org.postgresql:postgresql'
    testImplementation 'org.springframework.kafka:spring-kafka-test'
    testImplementation 'org.awaitility:awaitility:4.2.2'
    testRuntimeOnly 'com.h2database:h2'
}
```

- [ ] **Step 3: 단위 테스트만 실행 (통합 테스트 제외)**

```bash
./gradlew :services:notification:test --tests "com.picpay.notification.consumer.PaymentEventConsumerTest" -i
```

Expected: 3개 단위 테스트 PASS

### 5-B: 수동 E2E 검증 (Docker Compose + 실 Kafka)

- [ ] **Step 4: Docker Compose 기동**

```bash
cd /Users/picpal/Desktop/workspace/picpay
docker-compose up -d
docker-compose ps
```

Expected: kafka, zookeeper, postgres, redis 모두 Up 상태

- [ ] **Step 5: Payment Service 기동 (토픽 자동 생성)**

```bash
./gradlew :services:payment:bootRun &
sleep 15
```

Expected: 로그에 `payment.completed`, `payment.failed`, `payment.cancelled`, `billing.executed` 토픽 생성 메시지 확인

- [ ] **Step 6: Kafka 토픽 목록 확인**

```bash
docker exec picpay-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

Expected 출력:
```
billing.executed
payment.cancelled
payment.completed
payment.failed
```

- [ ] **Step 7: Notification Service 기동**

```bash
./gradlew :services:notification:bootRun &
sleep 10
```

Expected: 로그에 `notification-group` consumer group 등록 메시지

- [ ] **Step 8: 결제 승인 API 호출**

```bash
curl -s -X POST http://localhost:8081/v1/payments \
  -H "Content-Type: application/json" \
  -d '{"merchantId":"mer_001","orderId":"order-e2e-001","tokenId":"tok_abc","amount":10000,"method":"CARD"}' \
  | python3 -m json.tool
```

Expected: `{"success":true,"data":{"status":"PAID",...}}`

- [ ] **Step 9: Notification 로그 확인**

Payment Service 로그에서:
```
[Outbox] Published: topic=payment.completed, aggregateId=TSVR01...
```

Notification Service 로그에서:
```
[Notification] Processing: topic=payment.completed, key=TSVR01..., eventId=1
[Notification] Payload: {"tid":"TSVR01...","status":"PAID",...}
```

- [ ] **Step 10: Consumer Lag = 0 확인**

```bash
docker exec picpay-kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group notification-group
```

Expected: LAG 컬럼 값이 모두 0

- [ ] **Step 11: 최종 커밋**

```bash
git add services/notification/build.gradle
git add services/notification/src/test/java/com/picpay/notification/consumer/PaymentEventConsumerIntegrationTest.java
git commit -m "feat(notification): add E2E integration test and manual verification guide (Layer 4)"
```

---

## 자가 검토

### 스펙 커버리지 점검

| 스펙 요건 | 구현 위치 |
|----------|----------|
| Kafka 토픽 4개 (3 파티션) | Task 1 KafkaConfig.java NewTopic 4개 |
| Producer `acks=all`, `idempotence=true` | Task 1 application.yml |
| Outbox Poller → KafkaTemplate.send() | Task 2 OutboxPoller.java |
| 발행 실패 시 `retry_count++`, `DEAD` 전이 | Task 2 (기존 markFailed() 재사용) |
| Notification `@KafkaListener` 3개 토픽 | Task 3 PaymentEventConsumer.java |
| `enable.auto.commit=false`, `concurrency=3` | Task 3 application.yml |
| 수동 커밋 `ack.acknowledge()` | Task 3/4 PaymentEventConsumer.java |
| `processed_events` INSERT ON CONFLICT DO NOTHING | Task 4 ProcessedEventRepository.java |
| 동일 eventId 2회 수신 시 1회만 처리 | Task 4 PaymentEventConsumer.consume() |
| E2E 검증 (결제 → Outbox → Kafka → Notification) | Task 5 S19 수동 검증 절차 |
| Consumer Lag = 0 확인 | Task 5 kafka-consumer-groups.sh |

### 타입/메서드 일관성

- `OutboxEvent.getId()` — Task 2 OutboxPoller에서 `X-Event-Id` 헤더 값으로 사용, `OutboxEvent.java`에 public getter 존재 ✓
- `processedEventRepository.insertIfNotExists(eventId, topic)` — Task 4 정의, Task 4 consume() 내부에서 동일 시그니처 사용 ✓
- `OutboxStatus.PUBLISHED / FAILED / DEAD` — Task 2 OutboxPollerTest에서 사용, Layer 3에서 이미 정의된 enum ✓
- `PaymentEventConsumer` 생성자: `ProcessedEventRepository` 1개 인자 — Task 4 정의, Task 4 테스트 setUp() 일치 ✓

### 플레이스홀더 점검

- Task 5 통합 테스트: H2 + `ON CONFLICT DO NOTHING` 호환성 이슈 명시, 수동 검증 절차를 대안으로 제공 ✓
- 모든 코드 블록 완전 ✓
- "TBD", "TODO" 없음 ✓
