package com.picpay.token.service;

import com.picpay.token.domain.EasyPayMethod;
import com.picpay.token.domain.EasyPayMethodStatus;
import com.picpay.token.dto.EasyPayMethodResponse;
import com.picpay.token.dto.RegisterEasyPayRequest;
import com.picpay.token.repository.EasyPayMethodRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EasyPayMethodServiceTest {

    @Mock
    private EasyPayMethodRepository easyPayMethodRepository;

    private EasyPayMethodService easyPayMethodService;

    @BeforeEach
    void setUp() {
        easyPayMethodService = new EasyPayMethodService(easyPayMethodRepository);
    }

    @Test
    void 간편결제_수단_등록() {
        given(easyPayMethodRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        RegisterEasyPayRequest request = new RegisterEasyPayRequest(
                "user_001", "tok_abc", "My Card");

        EasyPayMethodResponse response = easyPayMethodService.register(request);

        assertThat(response.methodId()).startsWith("epm_");
        assertThat(response.userId()).isEqualTo("user_001");
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void 사용자_간편결제_수단_목록_조회() {
        EasyPayMethod method = EasyPayMethod.create("epm_001", "user_001", "tok_abc", "My Card");
        given(easyPayMethodRepository.findByUserIdAndStatus("user_001", EasyPayMethodStatus.ACTIVE))
                .willReturn(List.of(method));

        List<EasyPayMethodResponse> result = easyPayMethodService.listByUser("user_001");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).methodId()).isEqualTo("epm_001");
    }

    @Test
    void 간편결제_수단_삭제_시_status_DELETED로_변경() {
        EasyPayMethod method = EasyPayMethod.create("epm_001", "user_001", "tok_abc", "My Card");
        given(easyPayMethodRepository.findByMethodId("epm_001")).willReturn(Optional.of(method));
        given(easyPayMethodRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        easyPayMethodService.delete("epm_001");

        verify(easyPayMethodRepository).save(method);
        assertThat(method.getStatus()).isEqualTo(EasyPayMethodStatus.DELETED);
    }
}
