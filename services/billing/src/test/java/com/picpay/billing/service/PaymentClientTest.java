package com.picpay.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentClientTest {

    @Mock RestClient paymentRestClient;
    @Mock RestClient.RequestBodyUriSpec uriSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;
    @InjectMocks PaymentClient paymentClient;

    @Test
    void requestPayment_success_returnsTid() throws Exception {
        JsonNode json = new ObjectMapper().readTree(
                "{\"success\":true,\"data\":{\"tid\":\"TXN-001\",\"status\":\"PAID\"}}");

        when(paymentRestClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri("/v1/payments")).thenReturn(bodySpec);
        when(bodySpec.body(any(Object.class))).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(JsonNode.class)).thenReturn(json);

        String tid = paymentClient.requestPayment("mer_001", "order-001", "tok_abc", 10000L);

        assertThat(tid).isEqualTo("TXN-001");
    }
}
