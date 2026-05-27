package com.picpay.gateway.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.gateway.exception.UnauthorizedException;
import com.picpay.gateway.service.AuthService;
import org.springframework.http.HttpStatus;
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
            .onErrorResume(UnauthorizedException.class, e ->
                Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("UNAUTHORIZED", "Authentication failed"))))
            .onErrorResume(e ->
                Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("INTERNAL_ERROR", "Internal server error"))));
    }

    public record TokenResponse(String token) {}
}
