# Layer 2: Token Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AES-256-GCM 기반 카드 토큰 발급/조회(Redis Cache-Aside) + 간편결제 수단 CRUD를 제공하는 독립 Token Service를 구축한다.

**Architecture:** Token Service(port 8083)는 의존성 없는 독립 서비스. VaultService가 AES-256-GCM으로 카드번호/유효기간을 암호화하고, CVC는 메모리에서 즉시 폐기(DB 미저장). 토큰 조회는 Redis Cache-Aside(TTL 5분)로 DB 부하를 최소화한다.

**Tech Stack:** Java 21, Spring Boot 3.4.0, Spring Data JPA, Spring Data Redis, PostgreSQL 16(token 스키마), AES/GCM/NoPadding

---

## 파일 구조

```
services/token/
├── build.gradle                                        (modify: Redis 의존성 추가)
├── src/
│   ├── main/java/com/picpay/token/
│   │   ├── TokenApplication.java                       (exists)
│   │   ├── crypto/
│   │   │   └── VaultService.java                       (Task 1)
│   │   ├── domain/
│   │   │   ├── CardToken.java                          (Task 1)
│   │   │   ├── CardTokenStatus.java                    (Task 1)
│   │   │   ├── EasyPayMethod.java                      (Task 4)
│   │   │   └── EasyPayMethodStatus.java                (Task 4)
│   │   ├── repository/
│   │   │   ├── CardTokenRepository.java                (Task 1)
│   │   │   └── EasyPayMethodRepository.java            (Task 4)
│   │   ├── dto/
│   │   │   ├── IssueCardTokenRequest.java              (Task 2)
│   │   │   ├── CardTokenResponse.java                  (Task 2)
│   │   │   ├── RegisterEasyPayRequest.java             (Task 4)
│   │   │   └── EasyPayMethodResponse.java              (Task 4)
│   │   ├── service/
│   │   │   ├── CardTokenService.java                   (Task 2 + Task 3)
│   │   │   └── EasyPayMethodService.java               (Task 4)
│   │   └── controller/
│   │       ├── CardTokenController.java                (Task 2 + Task 3)
│   │       └── EasyPayMethodController.java            (Task 4)
│   ├── main/resources/
│   │   └── application.yml                             (modify: Redis + vault key)
│   └── test/java/com/picpay/token/
│       ├── crypto/
│       │   └── VaultServiceTest.java                   (Task 1)
│       ├── service/
│       │   ├── CardTokenServiceTest.java               (Task 2 + Task 3)
│       │   └── EasyPayMethodServiceTest.java           (Task 4)
│       └── controller/
│           ├── CardTokenControllerTest.java            (Task 2 + Task 3)
│           └── EasyPayMethodControllerTest.java        (Task 4)
```

---

## Task 1: VaultService (AES-256-GCM) + CardToken 엔티티 + Repository (S5)

**Files:**
- Modify: `services/token/build.gradle`
- Modify: `services/token/src/main/resources/application.yml`
- Create: `services/token/src/main/java/com/picpay/token/crypto/VaultService.java`
- Create: `services/token/src/main/java/com/picpay/token/domain/CardToken.java`
- Create: `services/token/src/main/java/com/picpay/token/domain/CardTokenStatus.java`
- Create: `services/token/src/main/java/com/picpay/token/repository/CardTokenRepository.java`
- Create: `services/token/src/test/java/com/picpay/token/crypto/VaultServiceTest.java`

- [ ] **Step 1: `services/token/build.gradle` 수정 — Redis 의존성 추가**

```groovy
plugins {
    id 'org.springframework.boot'
}

dependencies {
    implementation project(':common')
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    runtimeOnly 'org.postgresql:postgresql'
}
```

- [ ] **Step 2: `services/token/src/main/resources/application.yml` 수정 — Redis + vault key 추가**

```yaml
spring:
  application:
    name: token-service
  threads:
    virtual:
      enabled: true
  datasource:
    url: jdbc:postgresql://localhost:5432/picpay?currentSchema=token
    username: picpay
    password: picpay
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: token
  flyway:
    enabled: false
  data:
    redis:
      host: localhost
      port: 6379

server:
  port: 8083

management:
  endpoints:
    web:
      exposure:
        include: health,info

vault:
  aes-key: "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY="
```

> **주의:** `vault.aes-key`는 base64 인코딩된 32바이트(256-bit) 키. 위 값은 로컬 개발 전용.
> 실제 운영 키 생성: `python3 -c "import os,base64; print(base64.b64encode(os.urandom(32)).decode())"`

- [ ] **Step 3: 실패 테스트 작성**

`services/token/src/test/java/com/picpay/token/crypto/VaultServiceTest.java`:

```java
package com.picpay.token.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.KeyGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultServiceTest {

    private VaultService vaultService;

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        String base64Key = Base64.getEncoder().encodeToString(keyGen.generateKey().getEncoded());
        vaultService = new VaultService(base64Key);
    }

    @Test
    void encrypt_후_decrypt_하면_원문_복원() {
        String plaintext = "4111111111111111";

        String encrypted = vaultService.encrypt(plaintext);
        String decrypted = vaultService.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void 동일_평문이라도_암호화_결과는_매번_다름() {
        String plaintext = "4111111111111111";

        String enc1 = vaultService.encrypt(plaintext);
        String enc2 = vaultService.encrypt(plaintext);

        assertThat(enc1).isNotEqualTo(enc2);
    }

    @Test
    void 변조된_암호문_복호화_시_예외() {
        String encrypted = vaultService.encrypt("test");
        String tampered = encrypted.substring(0, encrypted.length() - 4) + "XXXX";

        assertThatThrownBy(() -> vaultService.decrypt(tampered))
                .isInstanceOf(RuntimeException.class);
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test --tests "com.picpay.token.crypto.VaultServiceTest" 2>&1 | tail -10
```

Expected: FAIL — `VaultService` 클래스 없음

- [ ] **Step 5: `VaultService` 구현**

`services/token/src/main/java/com/picpay/token/crypto/VaultService.java`:

```java
package com.picpay.token.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@Component
public class VaultService {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_BIT_LENGTH = 128;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final byte[] keyBytes;

    public VaultService(@Value("${vault.aes-key}") String base64Key) {
        this.keyBytes = Base64.getDecoder().decode(base64Key);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            new SecureRandom().nextBytes(nonce);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BIT_LENGTH, nonce));

            byte[] cipherWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] result = new byte[NONCE_LENGTH + cipherWithTag.length];
            System.arraycopy(nonce, 0, result, 0, NONCE_LENGTH);
            System.arraycopy(cipherWithTag, 0, result, NONCE_LENGTH, cipherWithTag.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public String decrypt(String encryptedBase64) {
        try {
            byte[] data = Base64.getDecoder().decode(encryptedBase64);
            byte[] nonce = Arrays.copyOfRange(data, 0, NONCE_LENGTH);
            byte[] cipherWithTag = Arrays.copyOfRange(data, NONCE_LENGTH, data.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(TAG_BIT_LENGTH, nonce));

            return new String(cipher.doFinal(cipherWithTag), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}
```

- [ ] **Step 6: 테스트 통과 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test --tests "com.picpay.token.crypto.VaultServiceTest" 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 7: `CardTokenStatus` 열거형 생성**

`services/token/src/main/java/com/picpay/token/domain/CardTokenStatus.java`:

```java
package com.picpay.token.domain;

public enum CardTokenStatus {
    ACTIVE, DELETED
}
```

- [ ] **Step 8: `CardToken` 엔티티 생성**

`services/token/src/main/java/com/picpay/token/domain/CardToken.java`:

```java
package com.picpay.token.domain;

import com.picpay.common.entity.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "card_tokens", schema = "token")
public class CardToken extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_id", nullable = false, unique = true)
    private String tokenId;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(name = "card_number_enc", nullable = false, columnDefinition = "TEXT")
    private String cardNumberEnc;

    @Column(name = "card_expiry_enc", nullable = false, columnDefinition = "TEXT")
    private String cardExpiryEnc;

    @Column(name = "card_last_four", nullable = false, length = 4)
    private String cardLastFour;

    @Column(name = "card_number_deleted_at")
    private LocalDateTime cardNumberDeletedAt;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private CardTokenStatus status = CardTokenStatus.ACTIVE;

    protected CardToken() {}

    public static CardToken create(String tokenId, String merchantId,
                                   String cardNumberEnc, String cardExpiryEnc,
                                   String cardLastFour) {
        CardToken t = new CardToken();
        t.tokenId = tokenId;
        t.merchantId = merchantId;
        t.cardNumberEnc = cardNumberEnc;
        t.cardExpiryEnc = cardExpiryEnc;
        t.cardLastFour = cardLastFour;
        return t;
    }

    public Long getId() { return id; }
    public String getTokenId() { return tokenId; }
    public String getMerchantId() { return merchantId; }
    public String getCardNumberEnc() { return cardNumberEnc; }
    public String getCardExpiryEnc() { return cardExpiryEnc; }
    public String getCardLastFour() { return cardLastFour; }
    public CardTokenStatus getStatus() { return status; }
    public LocalDateTime getCardNumberDeletedAt() { return cardNumberDeletedAt; }
}
```

- [ ] **Step 9: `CardTokenRepository` 생성**

`services/token/src/main/java/com/picpay/token/repository/CardTokenRepository.java`:

```java
package com.picpay.token.repository;

import com.picpay.token.domain.CardToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CardTokenRepository extends JpaRepository<CardToken, Long> {
    Optional<CardToken> findByTokenId(String tokenId);
}
```

- [ ] **Step 10: 빌드 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:build -x test 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 11: Commit**

```bash
cd /Users/picpal/Desktop/workspace/picpay && \
git add services/token/ && \
git commit -m "feat(token): add VaultService AES-256-GCM, CardToken entity and repository"
```

---

## Task 2: 카드 토큰 발급 API — POST /v1/tokens/card (S6)

**Files:**
- Create: `services/token/src/main/java/com/picpay/token/dto/IssueCardTokenRequest.java`
- Create: `services/token/src/main/java/com/picpay/token/dto/CardTokenResponse.java`
- Create: `services/token/src/main/java/com/picpay/token/service/CardTokenService.java`
- Create: `services/token/src/main/java/com/picpay/token/controller/CardTokenController.java`
- Create: `services/token/src/test/java/com/picpay/token/service/CardTokenServiceTest.java`
- Create: `services/token/src/test/java/com/picpay/token/controller/CardTokenControllerTest.java`

- [ ] **Step 1: DTO 생성**

`services/token/src/main/java/com/picpay/token/dto/IssueCardTokenRequest.java`:

```java
package com.picpay.token.dto;

public record IssueCardTokenRequest(
        String merchantId,
        String cardNumber,
        String cardExpiry,
        String cardCvc
) {}
```

`services/token/src/main/java/com/picpay/token/dto/CardTokenResponse.java`:

```java
package com.picpay.token.dto;

import com.picpay.token.domain.CardToken;

public record CardTokenResponse(
        String tokenId,
        String merchantId,
        String cardLastFour,
        String status
) {
    public static CardTokenResponse from(CardToken token) {
        return new CardTokenResponse(
                token.getTokenId(),
                token.getMerchantId(),
                token.getCardLastFour(),
                token.getStatus().name()
        );
    }
}
```

- [ ] **Step 2: 실패 테스트 작성**

`services/token/src/test/java/com/picpay/token/service/CardTokenServiceTest.java`:

```java
package com.picpay.token.service;

import com.picpay.token.crypto.VaultService;
import com.picpay.token.domain.CardToken;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.repository.CardTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CardTokenServiceTest {

    @Mock
    private VaultService vaultService;
    @Mock
    private CardTokenRepository cardTokenRepository;

    private CardTokenService cardTokenService;

    @BeforeEach
    void setUp() {
        cardTokenService = new CardTokenService(vaultService, cardTokenRepository);
    }

    @Test
    void 토큰_발급_시_카드번호와_유효기간_암호화() {
        given(vaultService.encrypt("4111111111111111")).willReturn("enc_number");
        given(vaultService.encrypt("12/28")).willReturn("enc_expiry");
        given(cardTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        IssueCardTokenRequest request = new IssueCardTokenRequest(
                "mer_001", "4111111111111111", "12/28", "123");

        CardTokenResponse response = cardTokenService.issue(request);

        assertThat(response.tokenId()).startsWith("tok_");
        assertThat(response.cardLastFour()).isEqualTo("1111");
        assertThat(response.merchantId()).isEqualTo("mer_001");
    }

    @Test
    void 토큰_발급_시_CVC는_저장되지_않음() {
        given(vaultService.encrypt(anyString())).willReturn("encrypted");
        given(cardTokenRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        IssueCardTokenRequest request = new IssueCardTokenRequest(
                "mer_001", "4111111111111111", "12/28", "123");

        cardTokenService.issue(request);

        // cardNumber와 cardExpiry만 암호화, CVC("123")는 encrypt 호출 없음
        verify(vaultService, never()).encrypt("123");
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test --tests "com.picpay.token.service.CardTokenServiceTest" 2>&1 | tail -10
```

Expected: FAIL

- [ ] **Step 4: `CardTokenService` 구현**

`services/token/src/main/java/com/picpay/token/service/CardTokenService.java`:

```java
package com.picpay.token.service;

import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.token.crypto.VaultService;
import com.picpay.token.domain.CardToken;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.repository.CardTokenRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CardTokenService {

    private final VaultService vaultService;
    private final CardTokenRepository cardTokenRepository;

    public CardTokenService(VaultService vaultService,
                            CardTokenRepository cardTokenRepository) {
        this.vaultService = vaultService;
        this.cardTokenRepository = cardTokenRepository;
    }

    @Transactional
    public CardTokenResponse issue(IssueCardTokenRequest request) {
        String cardNumberEnc = vaultService.encrypt(request.cardNumber());
        String cardExpiryEnc = vaultService.encrypt(request.cardExpiry());
        // CVC는 암호화하지 않고 즉시 폐기 (PCI-DSS 3.2)

        String cardLastFour = request.cardNumber()
                .substring(request.cardNumber().length() - 4);
        String tokenId = "tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        CardToken saved = cardTokenRepository.save(
                CardToken.create(tokenId, request.merchantId(),
                        cardNumberEnc, cardExpiryEnc, cardLastFour));

        return CardTokenResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public CardTokenResponse findByTokenId(String tokenId) {
        CardToken token = cardTokenRepository.findByTokenId(tokenId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_NOT_FOUND));
        return CardTokenResponse.from(token);
    }
}
```

- [ ] **Step 5: `CardTokenController` 구현**

`services/token/src/main/java/com/picpay/token/controller/CardTokenController.java`:

```java
package com.picpay.token.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.service.CardTokenService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/tokens")
public class CardTokenController {

    private final CardTokenService cardTokenService;

    public CardTokenController(CardTokenService cardTokenService) {
        this.cardTokenService = cardTokenService;
    }

    @PostMapping("/card")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CardTokenResponse> issue(@RequestBody IssueCardTokenRequest request) {
        return ApiResponse.ok(cardTokenService.issue(request));
    }

    @GetMapping("/{tokenId}")
    public ApiResponse<CardTokenResponse> findByTokenId(@PathVariable String tokenId) {
        return ApiResponse.ok(cardTokenService.findByTokenId(tokenId));
    }
}
```

- [ ] **Step 6: 컨트롤러 테스트 작성**

`services/token/src/test/java/com/picpay/token/controller/CardTokenControllerTest.java`:

```java
package com.picpay.token.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.common.exception.GlobalExceptionHandler;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.service.CardTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CardTokenController.class)
@Import(GlobalExceptionHandler.class)
class CardTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private CardTokenService cardTokenService;

    @Test
    void POST_카드토큰_발급_201_반환() throws Exception {
        CardTokenResponse response = new CardTokenResponse(
                "tok_abc123", "mer_001", "1111", "ACTIVE");
        given(cardTokenService.issue(any())).willReturn(response);

        IssueCardTokenRequest request = new IssueCardTokenRequest(
                "mer_001", "4111111111111111", "12/28", "123");

        mockMvc.perform(post("/v1/tokens/card")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenId").value("tok_abc123"))
                .andExpect(jsonPath("$.data.cardLastFour").value("1111"));
    }

    @Test
    void GET_존재하지_않는_토큰_조회_시_404() throws Exception {
        given(cardTokenService.findByTokenId("tok_unknown"))
                .willThrow(new BusinessException(ErrorCode.TOKEN_NOT_FOUND));

        mockMvc.perform(get("/v1/tokens/tok_unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("TOKEN_NOT_FOUND"));
    }
}
```

- [ ] **Step 7: 전체 테스트 통과 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL, 5+ tests passed

- [ ] **Step 8: Commit**

```bash
cd /Users/picpal/Desktop/workspace/picpay && \
git add services/token/src/ && \
git commit -m "feat(token): add card token issuance API POST /v1/tokens/card"
```

---

## Task 3: 토큰 조회 Redis Cache-Aside — GET /v1/tokens/{tokenId} (S7)

**Files:**
- Modify: `services/token/src/main/java/com/picpay/token/service/CardTokenService.java`
- Modify: `services/token/src/test/java/com/picpay/token/service/CardTokenServiceTest.java`

> Task 2에서 `findByTokenId()`는 DB만 조회. Task 3에서 Redis Cache-Aside를 추가한다.
> `GET /v1/tokens/{tokenId}` 엔드포인트는 이미 Task 2에서 생성됨 — 서비스 로직만 변경.

- [ ] **Step 1: 실패 테스트 추가**

`services/token/src/test/java/com/picpay/token/service/CardTokenServiceTest.java`에 테스트 메서드 추가:

```java
// 클래스 상단에 import 추가:
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import java.time.Duration;

// @Mock 필드 추가:
@Mock
private StringRedisTemplate redisTemplate;
@Mock
private ValueOperations<String, String> valueOps;

// setUp() 메서드 수정:
@BeforeEach
void setUp() {
    cardTokenService = new CardTokenService(vaultService, cardTokenRepository, redisTemplate, new ObjectMapper());
}

// 새 테스트 메서드 추가:
@Test
void 토큰_조회_시_캐시_히트면_DB_조회_안함() throws Exception {
    String tokenId = "tok_abc";
    String cached = """
            {"tokenId":"tok_abc","merchantId":"mer_001","cardLastFour":"1111","status":"ACTIVE"}
            """;
    given(redisTemplate.opsForValue()).willReturn(valueOps);
    given(valueOps.get("token:tok_abc")).willReturn(cached);

    CardTokenResponse result = cardTokenService.findByTokenId(tokenId);

    assertThat(result.tokenId()).isEqualTo("tok_abc");
    verify(cardTokenRepository, never()).findByTokenId(anyString());
}

@Test
void 토큰_조회_캐시_미스_시_DB_조회_후_캐시_저장() {
    String tokenId = "tok_abc";
    CardToken token = CardToken.create("tok_abc", "mer_001", "enc_num", "enc_exp", "1111");
    given(redisTemplate.opsForValue()).willReturn(valueOps);
    given(valueOps.get("token:tok_abc")).willReturn(null);
    given(cardTokenRepository.findByTokenId("tok_abc")).willReturn(java.util.Optional.of(token));

    CardTokenResponse result = cardTokenService.findByTokenId(tokenId);

    assertThat(result.cardLastFour()).isEqualTo("1111");
    verify(valueOps).set(anyString(), anyString(), any(Duration.class));
}
```

- [ ] **Step 2: 테스트 실패 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test --tests "com.picpay.token.service.CardTokenServiceTest" 2>&1 | tail -10
```

Expected: FAIL — `CardTokenService` 생성자 인자 불일치

- [ ] **Step 3: `CardTokenService` — Redis Cache-Aside 추가**

`services/token/src/main/java/com/picpay/token/service/CardTokenService.java` 전체 교체:

```java
package com.picpay.token.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.BusinessException;
import com.picpay.common.exception.ErrorCode;
import com.picpay.token.crypto.VaultService;
import com.picpay.token.domain.CardToken;
import com.picpay.token.dto.CardTokenResponse;
import com.picpay.token.dto.IssueCardTokenRequest;
import com.picpay.token.repository.CardTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Service
public class CardTokenService {

    private static final Logger log = LoggerFactory.getLogger(CardTokenService.class);
    private static final String CACHE_PREFIX = "token:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final VaultService vaultService;
    private final CardTokenRepository cardTokenRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CardTokenService(VaultService vaultService,
                            CardTokenRepository cardTokenRepository,
                            StringRedisTemplate redisTemplate,
                            ObjectMapper objectMapper) {
        this.vaultService = vaultService;
        this.cardTokenRepository = cardTokenRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CardTokenResponse issue(IssueCardTokenRequest request) {
        String cardNumberEnc = vaultService.encrypt(request.cardNumber());
        String cardExpiryEnc = vaultService.encrypt(request.cardExpiry());
        // CVC는 암호화하지 않고 즉시 폐기 (PCI-DSS 3.2)

        String cardLastFour = request.cardNumber()
                .substring(request.cardNumber().length() - 4);
        String tokenId = "tok_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        CardToken saved = cardTokenRepository.save(
                CardToken.create(tokenId, request.merchantId(),
                        cardNumberEnc, cardExpiryEnc, cardLastFour));

        return CardTokenResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public CardTokenResponse findByTokenId(String tokenId) {
        String cacheKey = CACHE_PREFIX + tokenId;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, CardTokenResponse.class);
            } catch (JsonProcessingException e) {
                log.warn("Cache deserialization failed for key={}", cacheKey);
            }
        }

        CardToken token = cardTokenRepository.findByTokenId(tokenId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TOKEN_NOT_FOUND));

        CardTokenResponse response = CardTokenResponse.from(token);
        try {
            redisTemplate.opsForValue().set(cacheKey,
                    objectMapper.writeValueAsString(response), CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Cache serialization failed for key={}", cacheKey);
        }
        return response;
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
cd /Users/picpal/Desktop/workspace/picpay && \
git add services/token/src/ && \
git commit -m "feat(token): add Redis Cache-Aside for GET /v1/tokens/{tokenId} (TTL 5min)"
```

---

## Task 4: 간편결제 수단 CRUD (S8)

**Files:**
- Create: `services/token/src/main/java/com/picpay/token/domain/EasyPayMethodStatus.java`
- Create: `services/token/src/main/java/com/picpay/token/domain/EasyPayMethod.java`
- Create: `services/token/src/main/java/com/picpay/token/repository/EasyPayMethodRepository.java`
- Create: `services/token/src/main/java/com/picpay/token/dto/RegisterEasyPayRequest.java`
- Create: `services/token/src/main/java/com/picpay/token/dto/EasyPayMethodResponse.java`
- Create: `services/token/src/main/java/com/picpay/token/service/EasyPayMethodService.java`
- Create: `services/token/src/main/java/com/picpay/token/controller/EasyPayMethodController.java`
- Create: `services/token/src/test/java/com/picpay/token/service/EasyPayMethodServiceTest.java`
- Create: `services/token/src/test/java/com/picpay/token/controller/EasyPayMethodControllerTest.java`

- [ ] **Step 1: `EasyPayMethodStatus` + `EasyPayMethod` 엔티티 생성**

`services/token/src/main/java/com/picpay/token/domain/EasyPayMethodStatus.java`:

```java
package com.picpay.token.domain;

public enum EasyPayMethodStatus {
    ACTIVE, DELETED
}
```

`services/token/src/main/java/com/picpay/token/domain/EasyPayMethod.java`:

```java
package com.picpay.token.domain;

import com.picpay.common.entity.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "easy_pay_methods", schema = "token")
public class EasyPayMethod extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "method_id", nullable = false, unique = true)
    private String methodId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "token_id", nullable = false)
    private String tokenId;

    @Column(name = "method_name")
    private String methodName;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    private EasyPayMethodStatus status = EasyPayMethodStatus.ACTIVE;

    protected EasyPayMethod() {}

    public static EasyPayMethod create(String methodId, String userId,
                                       String tokenId, String methodName) {
        EasyPayMethod m = new EasyPayMethod();
        m.methodId = methodId;
        m.userId = userId;
        m.tokenId = tokenId;
        m.methodName = methodName;
        return m;
    }

    public void delete() {
        this.status = EasyPayMethodStatus.DELETED;
    }

    public Long getId() { return id; }
    public String getMethodId() { return methodId; }
    public String getUserId() { return userId; }
    public String getTokenId() { return tokenId; }
    public String getMethodName() { return methodName; }
    public EasyPayMethodStatus getStatus() { return status; }
}
```

- [ ] **Step 2: `EasyPayMethodRepository` 생성**

`services/token/src/main/java/com/picpay/token/repository/EasyPayMethodRepository.java`:

```java
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
```

- [ ] **Step 3: DTO 생성**

`services/token/src/main/java/com/picpay/token/dto/RegisterEasyPayRequest.java`:

```java
package com.picpay.token.dto;

public record RegisterEasyPayRequest(
        String userId,
        String tokenId,
        String methodName
) {}
```

`services/token/src/main/java/com/picpay/token/dto/EasyPayMethodResponse.java`:

```java
package com.picpay.token.dto;

import com.picpay.token.domain.EasyPayMethod;

public record EasyPayMethodResponse(
        String methodId,
        String userId,
        String tokenId,
        String methodName,
        String status
) {
    public static EasyPayMethodResponse from(EasyPayMethod m) {
        return new EasyPayMethodResponse(
                m.getMethodId(), m.getUserId(), m.getTokenId(),
                m.getMethodName(), m.getStatus().name());
    }
}
```

- [ ] **Step 4: 실패 테스트 작성**

`services/token/src/test/java/com/picpay/token/service/EasyPayMethodServiceTest.java`:

```java
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
```

- [ ] **Step 5: 테스트 실패 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test --tests "com.picpay.token.service.EasyPayMethodServiceTest" 2>&1 | tail -10
```

Expected: FAIL

- [ ] **Step 6: `EasyPayMethodService` 구현**

`services/token/src/main/java/com/picpay/token/service/EasyPayMethodService.java`:

```java
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
```

- [ ] **Step 7: `EasyPayMethodController` 구현**

`services/token/src/main/java/com/picpay/token/controller/EasyPayMethodController.java`:

```java
package com.picpay.token.controller;

import com.picpay.common.response.ApiResponse;
import com.picpay.token.dto.EasyPayMethodResponse;
import com.picpay.token.dto.RegisterEasyPayRequest;
import com.picpay.token.service.EasyPayMethodService;
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
            @RequestBody RegisterEasyPayRequest request) {
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
```

- [ ] **Step 8: 컨트롤러 테스트 작성**

`services/token/src/test/java/com/picpay/token/controller/EasyPayMethodControllerTest.java`:

```java
package com.picpay.token.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.picpay.common.exception.GlobalExceptionHandler;
import com.picpay.token.dto.EasyPayMethodResponse;
import com.picpay.token.dto.RegisterEasyPayRequest;
import com.picpay.token.service.EasyPayMethodService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(EasyPayMethodController.class)
@Import(GlobalExceptionHandler.class)
class EasyPayMethodControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EasyPayMethodService easyPayMethodService;

    @Test
    void POST_간편결제_수단_등록_201_반환() throws Exception {
        EasyPayMethodResponse response = new EasyPayMethodResponse(
                "epm_abc", "user_001", "tok_abc", "My Card", "ACTIVE");
        given(easyPayMethodService.register(any())).willReturn(response);

        RegisterEasyPayRequest request = new RegisterEasyPayRequest(
                "user_001", "tok_abc", "My Card");

        mockMvc.perform(post("/v1/easy-pay/methods")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.methodId").value("epm_abc"));
    }

    @Test
    void GET_사용자_간편결제_수단_목록_조회() throws Exception {
        EasyPayMethodResponse m = new EasyPayMethodResponse(
                "epm_001", "user_001", "tok_abc", "My Card", "ACTIVE");
        given(easyPayMethodService.listByUser("user_001")).willReturn(List.of(m));

        mockMvc.perform(get("/v1/easy-pay/methods").param("userId", "user_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].methodId").value("epm_001"));
    }

    @Test
    void DELETE_간편결제_수단_삭제_204_반환() throws Exception {
        mockMvc.perform(delete("/v1/easy-pay/methods/epm_001"))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 9: 전체 테스트 통과 확인**

```bash
cd /Users/picpal/Desktop/workspace/picpay && ./gradlew :services:token:test 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
cd /Users/picpal/Desktop/workspace/picpay && \
git add services/token/src/ && \
git commit -m "feat(token): add easy-pay methods CRUD POST/GET/DELETE /v1/easy-pay/methods"
```

---

## Layer 2 완료 체크리스트

- [ ] `./gradlew :services:token:test` 전체 통과 (10+ 테스트)
- [ ] VaultService: 암호화/복호화/변조감지 3개 테스트 통과
- [ ] CVC 미저장 검증 테스트 통과
- [ ] Redis Cache-Aside: 캐시 히트/미스 테스트 통과
- [ ] Easy-Pay CRUD 컨트롤러 테스트 통과
- [ ] git log에 4개 커밋 존재

완료 후 → **Layer 3 (Payment Service) 플랜**으로 이동
