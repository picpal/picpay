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
