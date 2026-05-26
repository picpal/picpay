package com.picpay.payment.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.payment.dto.CancelRequest;
import com.picpay.payment.dto.CancelResponse;
import com.picpay.payment.dto.PaymentRequest;
import com.picpay.payment.dto.PaymentResponse;
import com.picpay.payment.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PaymentResponse> approve(@Valid @RequestBody PaymentRequest request) {
        return ApiResponse.ok(paymentService.approve(request));
    }

    @GetMapping("/{tid}")
    public ApiResponse<PaymentResponse> findByTid(@PathVariable String tid) {
        return ApiResponse.ok(paymentService.findByTid(tid));
    }

    @PostMapping("/cancel")
    public ApiResponse<CancelResponse> cancel(@Valid @RequestBody CancelRequest request) {
        return ApiResponse.ok(paymentService.cancel(request));
    }
}
