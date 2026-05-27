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
