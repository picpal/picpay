package com.picpay.billing.repository;

import com.picpay.billing.domain.BillingHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingHistoryRepository extends JpaRepository<BillingHistory, Long> {
    List<BillingHistory> findByPlanIdOrderByCreatedAtDesc(String planId);
}
