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
