# Layer 7 배포 트러블슈팅 기록

> 날짜: 2026-05-28~29  
> 대상: GitHub Actions CD 파이프라인 (`cd.yml`)

---

## 발생한 문제들과 수정 내역

### Fix 1 — 보안 그룹 이름 `sg-*` 불허
**오류:** `InvalidParameterValue: Group names may not be in the format sg-*`  
**원인:** AWS는 보안 그룹 이름에 `sg-` 접두사 사용 불가  
**수정:** `sg-alb` → `picpay-alb`, `sg-was` → `picpay-was` 등으로 변경  

---

### Fix 2 — IPv6 IP 반환 문제
**오류:** `CIDR block 2a02:26f7:... is malformed`  
**원인:** `ifconfig.me`가 IPv6 주소 반환  
**수정:** `curl -s https://checkip.amazonaws.com` 으로 교체 (항상 IPv4 반환)

---

### Fix 3 — GitHub Actions → EC2-A SSH 타임아웃
**오류:** `connect to host port 22: Connection timed out`  
**원인:** `sg-was` 보안 그룹 SSH 규칙이 개발자 IP만 허용. GitHub Actions 러너 IP 차단  
**수정:** EC2-A 포트 22를 `0.0.0.0/0`으로 오픈  
**보안 고려:** PEM 키 인증만 허용(비밀번호 로그인 불가), 미사용 시 EC2 꺼둠 → 실질 위험 낮음

---

### Fix 4 — EC2-A → EC2-B SSH 권한 거부
**오류:** `Permission denied (publickey)`  
**원인:** EC2-A에 PEM 키 파일이 없어서 EC2-B 접속 불가  
**수정:** Setup SSH 단계에서 PEM 키를 EC2-A(`~/.ssh/picpay.pem`)에 SCP로 복사

---

### Fix 5 — EC2-B 배포 순서 오류 (token 서비스 unhealthy)
**오류:** `dependency failed to start: container ubuntu-token-1 is unhealthy`  
**원인:** token 서비스가 Redis에 연결 시도하는데, Redis(EC2-B)가 아직 미배포 상태  
**수정:** CD 순서 변경  
- **이전:** EC2-A WAS 배포 → EC2-B 미들웨어 배포  
- **이후:** EC2-B 미들웨어 배포 → 30초 대기 → EC2-A WAS 배포

---

### Fix 6 — Docker 이미지 이중 빌드 (성능)
**문제:** Gradle bootJar를 runner에서 한 번, Dockerfile 안에서 한 번 더 빌드  
**수정:** Dockerfile을 "복사 전용"으로 단순화
```dockerfile
# 이전 (20줄, Gradle 빌드 포함)
FROM eclipse-temurin:21-jdk-alpine AS builder
RUN ./gradlew :services:gateway:bootJar ...

# 이후 (7줄, JAR 복사만)
FROM eclipse-temurin:21-jre-alpine
COPY services/gateway/build/libs/*.jar app.jar
```
또한 5개 이미지 빌드를 `&` + `wait`으로 **병렬 실행**  
→ 빌드 시간 약 50% 단축

---

### Fix 7 — EC2_USER 변수 EC2-A 쉘에서 미인식
**오류:** `ssh usage: ...` (username 없이 `@10.0.2.81` 형태로 실행)  
**원인:** `EC2_USER`는 GitHub Actions 환경변수. EC2-A 원격 쉘에는 전달되지 않음  
**수정:** REMOTE 헤어독 안에 `EC2_USER=ubuntu` 명시적 선언 추가

---

### Fix 8 — 중첩 heredoc `${img}` 변수 소비
**오류:** `bash: /tmp/.tar.gz: No such file or directory` (`$img`가 빈 문자열)  
**원인:** YAML `run:` → `<<REMOTE` 헤어독 → EC2-A 인라인 커맨드의 3단계 처리 과정에서  
`\${img}` 이스케이프가 각 단계에서 소비됨  
**수정:** EC2-B 배포 로직을 `infra/deploy-ec2-b.sh` 파일로 분리  
EC2-A가 이 파일을 SCP로 EC2-B에 전달 후 `bash /tmp/deploy-ec2-b.sh` 직접 실행  
→ 헤어독 이스케이프 지옥 완전 해소

---

## 현재 인프라 상태

| 리소스 | ID / 값 |
|--------|---------|
| VPC | `vpc-04b169575ba814ebd` |
| EC2-A (WAS) | `i-0e607c38fe6b91b82` / `54.180.235.118` |
| EC2-B (Middleware) | `i-012520d2acc7a4c89` / `10.0.2.81` (private) |
| RDS | `picpay-db.cpyewcioig24.ap-northeast-2.rds.amazonaws.com` |
| 키 페어 | `picpay-key.pem` (`~/.ssh/picpay-key.pem`) |

## CD 파이프라인 최종 순서

```
1. Build JARs (Gradle, runner 캐시 활용)
2. Build WAS Docker images (병렬 5개 동시)
3. Pull & save middleware images
4. Setup SSH key (EC2-A에 PEM 키 복사 포함)
5. [EC2-B 먼저] 미들웨어 이미지 → EC2-A → EC2-B relay
6. deploy-ec2-b.sh 실행 (Redis, Kafka, Zookeeper 기동)
7. 30초 대기 (미들웨어 준비)
8. [EC2-A] WAS 이미지 복사 및 배포
9. 60초 대기 후 헬스체크
```
