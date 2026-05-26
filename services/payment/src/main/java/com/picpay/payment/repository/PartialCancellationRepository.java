package com.picpay.payment.repository;

import com.picpay.payment.domain.PartialCancellation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PartialCancellationRepository extends JpaRepository<PartialCancellation, Long> {
    List<PartialCancellation> findByPaymentId(Long paymentId);
}
