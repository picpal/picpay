package com.picpay.gateway.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.gateway.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/token")
    public Mono<ResponseEntity<ApiResponse<TokenResponse>>> getToken(
            @RequestHeader("X-Api-Key") String apiKey) {
        return authService.authenticate(apiKey)
            .map(token -> ResponseEntity.ok(ApiResponse.ok(new TokenResponse(token))))
            .onErrorReturn(ResponseEntity.status(401)
                .body(ApiResponse.error("UNAUTHORIZED", "Authentication failed")));
    }

    public record TokenResponse(String token) {}
}
