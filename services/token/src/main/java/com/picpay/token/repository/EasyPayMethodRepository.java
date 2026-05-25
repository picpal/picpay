package com.picpay.token.repository;

import com.picpay.token.domain.EasyPayMethod;
import com.picpay.token.domain.EasyPayMethodStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EasyPayMethodRepository extends JpaRepository<EasyPayMethod, Long> {
    List<EasyPayMethod> findByUserIdAndStatus(String userId, EasyPayMethodStatus status);
    Optional<EasyPayMethod> findByMethodId(String methodId);
}
