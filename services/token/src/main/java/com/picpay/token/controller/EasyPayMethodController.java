package com.picpay.token.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.token.dto.EasyPayMethodResponse;
import com.picpay.token.dto.RegisterEasyPayRequest;
import com.picpay.token.service.EasyPayMethodService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1/easy-pay/methods")
public class EasyPayMethodController {

    private final EasyPayMethodService easyPayMethodService;

    public EasyPayMethodController(EasyPayMethodService easyPayMethodService) {
        this.easyPayMethodService = easyPayMethodService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<EasyPayMethodResponse> register(
            @Valid @RequestBody RegisterEasyPayRequest request) {
        return ApiResponse.ok(easyPayMethodService.register(request));
    }

    @GetMapping
    public ApiResponse<List<EasyPayMethodResponse>> list(@RequestParam String userId) {
        return ApiResponse.ok(easyPayMethodService.listByUser(userId));
    }

    @DeleteMapping("/{methodId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String methodId) {
        easyPayMethodService.delete(methodId);
    }
}
