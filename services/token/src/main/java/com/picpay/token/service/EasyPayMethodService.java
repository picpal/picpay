package com.picpay.token.service;

import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.token.domain.EasyPayMethod;
import com.picpay.token.domain.EasyPayMethodStatus;
import com.picpay.token.dto.EasyPayMethodResponse;
import com.picpay.token.dto.RegisterEasyPayRequest;
import com.picpay.token.repository.EasyPayMethodRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class EasyPayMethodService {

    private final EasyPayMethodRepository easyPayMethodRepository;

    public EasyPayMethodService(EasyPayMethodRepository easyPayMethodRepository) {
        this.easyPayMethodRepository = easyPayMethodRepository;
    }

    @Transactional
    public EasyPayMethodResponse register(RegisterEasyPayRequest request) {
        String methodId = "epm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        EasyPayMethod saved = easyPayMethodRepository.save(
                EasyPayMethod.create(methodId, request.userId(),
                        request.tokenId(), request.methodName()));
        return EasyPayMethodResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<EasyPayMethodResponse> listByUser(String userId) {
        return easyPayMethodRepository
                .findByUserIdAndStatus(userId, EasyPayMethodStatus.ACTIVE)
                .stream()
                .map(EasyPayMethodResponse::from)
                .collect(Collectors.toList());
    }

    @Transactional
    public void delete(String methodId) {
        EasyPayMethod method = easyPayMethodRepository.findByMethodId(methodId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_NOT_FOUND));
        method.delete();
        easyPayMethodRepository.save(method);
    }
}
