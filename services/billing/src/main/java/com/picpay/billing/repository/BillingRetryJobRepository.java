package com.picpay.billing.repository;

import com.picpay.billing.domain.BillingRetryJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface BillingRetryJobRepository extends JpaRepository<BillingRetryJob, Long> {

    @Query("SELECT j FROM BillingRetryJob j WHERE j.status = 'PENDING' AND j.nextRetryAt <= :now")
    List<BillingRetryJob> findDueRetryJobs(@Param("now") LocalDateTime now);
}
