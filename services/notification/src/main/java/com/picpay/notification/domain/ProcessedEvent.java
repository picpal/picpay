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
