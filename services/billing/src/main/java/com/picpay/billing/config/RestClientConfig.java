package com.picpay.billing.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient paymentRestClient(@Value("${payment.service.url}") String paymentServiceUrl) {
        return RestClient.builder()
                .baseUrl(paymentServiceUrl)
                .build();
    }
}
