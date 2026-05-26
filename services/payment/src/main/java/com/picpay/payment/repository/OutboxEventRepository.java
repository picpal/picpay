package com.picpay.payment.repository;

import com.picpay.payment.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    @Query("SELECT e FROM OutboxEvent e WHERE e.status IN ('PENDING', 'FAILED') ORDER BY e.createdAt ASC LIMIT 100")
    List<OutboxEvent> findPendingOrFailed();
}
