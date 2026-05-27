package com.picpay.billing.repository;

import com.picpay.billing.domain.BillingPlan;
import com.picpay.billing.domain.BillingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {

    Optional<BillingPlan> findByPlanId(String planId);

    @Query("SELECT p FROM BillingPlan p WHERE p.status = :status AND p.nextBillingAt <= :now")
    List<BillingPlan> findDuePlans(@Param("status") BillingStatus status,
                                   @Param("now") LocalDateTime now);
}
