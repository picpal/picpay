# PicPay PRD v3.2 검토 설계 문서

> 검토 일자: 2026-05-25  
> 검토 기준: 비용 최적화 우선, 학습 목표 보존  
> 적용 대상: picpay_prd_v3.md

---

## 1. 검토 배경

### AWS 계정 상황
- 계정 생성: 2026년 5월 (7/15 이전 → 프리티어 적용)
- EC2 사용 패턴: 하루 4시간 × 31일 = **월 124시간**
- 프리티어 기준 (750h/월) 대비 16% 사용 → EC2, RDS 실질적으로 무료

### 실제 월 비용 (수정 후)

| 구분 | 프리티어 기간 (12개월) | 프리티어 만료 후 |
|------|:---:|:---:|
| EC2-A (t3.micro, WAS) | $0 | $1.61 |
| EC2-B (t3.micro, 미들웨어) | $1.61 | $1.61 |
| RDS (db.t3.micro) | $0 | $3.22 |
| ALB | $0 (테스트 시만 생성) | $0 (테스트 시만 생성) |
| EBS-B (20GB gp3) | $1.82 | $1.82 |
| Elastic IP | **$0 (제거)** | **$0 (제거)** |
| **합계** | **~$4/월** | **~$8/월** |

기존 PRD 시나리오 A ($15/월, 24시간 상시) 대비 **약 73% 절감**.

---

## 2. PRD 내부 불일치 수정 (3건)

### 수정 1 — Section 11 빌링 시퀀스 다이어그램

v3.1에서 `billing.retry` / `billing.dlq` Kafka 토픽을 DB 기반 재시도로 교체했으나,
Section 11 시퀀스 다이어그램에 해당 토픽이 잔존.

**수정 전:**
```
│  billing.retry 발행 (지수 백오프)──────▶│ Kafka
...
│  billing.dlq 발행────────────────────▶│ Kafka
```

**수정 후:**
```
│  [실패, retry < 3]
│   billing_retry_jobs INSERT
│   (next_retry_at = now + 2^retry × 30초)──▶│ DB
...
│  [실패, retry >= 3]
│   billing_retry_jobs → status=DEAD
│   billing.executed (status=FAILED) 발행──▶│ Kafka
```

---

### 수정 2 — Section 9 서비스 간 참조 규칙 테이블

**수정 전:**
```
| Billing → Billing | Kafka 이벤트 | 빌링 재시도 (billing.retry) |
```

**수정 후:**
해당 행 삭제. v3.1에서 DB 기반 재시도(billing_retry_jobs)로 대체됨.
서비스 간 Kafka 통신은 `billing.executed` 단일 토픽으로 통일.

---

### 수정 3 — Redis TID 키 패턴 통일

| 위치 | 기존 | 수정 후 |
|------|------|---------|
| Section 4 코드 | `tid:seq:{yyyyMMdd}` | `tid:seq:{yyyyMMdd}` (기준) |
| Section 6 키 설계 표 | `tid:sequence:{yyyyMMdd}` | **`tid:seq:{yyyyMMdd}`** |

Section 4 코드를 기준으로 Section 6 표를 통일.

---

## 3. EC2-A WAS 메모리 전략

### 배경
PRD는 t3.micro(1GB)와 t3.small(2GB)를 모두 언급하지만, 5개 Spring Boot 서비스를
1GB에서 운영하는 구체적인 방법이 없음. 프리티어 최대 활용을 위해 t3.micro 기준으로 먼저 시도.

### 서비스별 JVM 힙 설정

| 서비스 | `-Xmx` | 근거 |
|--------|-------:|------|
| API Gateway | 150MB | Spring Cloud Gateway, I/O 라우팅 위주 |
| Payment Service | 200MB | 핵심 트랜잭션 처리, 여유 확보 |
| Billing Service | 150MB | 스케줄러 + DB 조회 위주 |
| Token Service | 150MB | AES 암복호화, Redis 캐시 히트율 높음 |
| Notification Service | 100MB | Kafka Consumer + 로그 출력만 |
| **힙 합계** | **750MB** | |
| OS + JVM 오버헤드 | ~200MB | |
| **총 예상** | **~950MB** | 1GB 한계선 근접 |

### Docker Compose 메모리 제한 설정 (추가)

```yaml
# docker-compose.yml 각 서비스에 추가
services:
  gateway:
    environment:
      - JAVA_OPTS=-Xmx150m -Xms75m -XX:+UseG1GC
    deploy:
      resources:
        limits:
          memory: 200m

  payment:
    environment:
      - JAVA_OPTS=-Xmx200m -Xms100m -XX:+UseG1GC
    deploy:
      resources:
        limits:
          memory: 256m

  billing:
    environment:
      - JAVA_OPTS=-Xmx150m -Xms75m -XX:+UseG1GC
    deploy:
      resources:
        limits:
          memory: 200m

  token:
    environment:
      - JAVA_OPTS=-Xmx150m -Xms75m -XX:+UseG1GC
    deploy:
      resources:
        limits:
          memory: 200m

  notification:
    environment:
      - JAVA_OPTS=-Xmx100m -Xms50m -XX:+UseG1GC
    deploy:
      resources:
        limits:
          memory: 150m
```

### Sprint 1 메모리 검증 기준

```
검증 방법:
  ssh ec2-a "free -m"

통과 기준:
  5개 서비스 동시 기동 후 available > 50MB

실패 시 대응:
  t3.small로 전환 (월 $2.60 추가, 프리티어 외 과금)
  → 연간 추가 비용 $31.20 (약 42,000원)
```

---

## 4. Elastic IP 제거 + EC2 시작 자동화

### 변경 이유
- 2024.02.01 정책 변경으로 Elastic IP는 연결 중에도 $0.005/hr 과금
- 4시간/일 패턴에서 월 $3.65 → **연간 $43.80 고정 지출**
- EC2 Start/Stop 시 Public IP가 변경되지만, 스크립트로 자동화 가능

### start-ec2.sh (로컬 실행 스크립트, 신규 추가)

```bash
#!/bin/bash
# start-ec2.sh — EC2-A/B 시작 후 IP 자동 갱신

EC2_A_ID="i-xxxxxxxxxxxxxxxxx"   # WAS 서버 Instance ID
EC2_B_ID="i-yyyyyyyyyyyyyyyyy"   # 미들웨어 서버 Instance ID
REGION="ap-northeast-2"

# EC2-A, EC2-B 동시 시작
echo "Starting EC2-A (WAS) and EC2-B (middleware)..."
aws ec2 start-instances --instance-ids $EC2_A_ID $EC2_B_ID --region $REGION

# 실행 대기
aws ec2 wait instance-running --instance-ids $EC2_A_ID $EC2_B_ID --region $REGION

# EC2-A Public IP 획득 (외부 접속용)
PUBLIC_IP=$(aws ec2 describe-instances \
  --instance-ids $EC2_A_ID \
  --region $REGION \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)

# EC2-B는 같은 VPC 내 Private IP 사용 → Stop/Start해도 변경 안 됨
# Spring Boot 서비스의 Kafka/Redis 연결은 Private IP로 고정 설정 가능

echo "EC2-A Public IP: $PUBLIC_IP"

# SSH config 자동 갱신 (EC2-A만 Public 접속)
sed -i '' "s/HostName .*/HostName $PUBLIC_IP/" ~/.ssh/config

# GitHub Actions Secret 갱신 (gh CLI 필요)
gh secret set EC2_HOST --body "$PUBLIC_IP" --repo picpal/picpay

echo "Done. Connect: ssh picpay-was"
```

### ~/.ssh/config 예시

```
Host picpay-was
    HostName 0.0.0.0       # start-ec2.sh가 자동 갱신
    User ubuntu
    IdentityFile ~/.ssh/picpay-key.pem
    StrictHostKeyChecking no
```

---

## 5. ALB 동적 생성 전략

### 운영 방침
- **평소 (개발/디버깅):** ALB 없음 → `http://{EC2-A IP}:8080` 직접 접속
- **부하 테스트 시:** ALB 생성 → k6 실행 → ALB 즉시 삭제

### Security Group 조정 필요 (ALB 없는 개발 환경)

현재 PRD의 `sg-was`는 `sg-alb`에서만 8080-8084 허용으로 설계되어 있음.
ALB 없이 직접 접속하려면 개발 환경 전용 인바운드 규칙 추가 필요.

```
sg-was 추가 규칙 (개발 시에만):
  포트: 8080
  소스: 내 IP (개발자 공인 IP)
  목적: ALB 없이 API Gateway 직접 접속

부하 테스트 시:
  위 규칙 제거 → ALB(sg-alb)를 통해서만 접근
```

> 내 IP는 `curl ifconfig.me`로 확인. EC2 Console → Security Group → Inbound Rules에서 수동 관리.

### alb-create.sh / alb-delete.sh (신규 추가)

```bash
# alb-create.sh
REGION="ap-northeast-2"
VPC_ID="vpc-xxx"
SUBNET_ID="subnet-xxx"     # Public Subnet ID
SG_ALB="sg-xxx"            # sg-alb Security Group ID
EC2_A_ID="i-xxx"           # EC2-A Instance ID

# 1. ALB 생성
ALB_ARN=$(aws elbv2 create-load-balancer \
  --name picpay-alb \
  --subnets $SUBNET_ID \
  --security-groups $SG_ALB \
  --type application \
  --region $REGION \
  --query 'LoadBalancers[0].LoadBalancerArn' \
  --output text)

# 2. Target Group 생성 (API Gateway :8080)
TG_ARN=$(aws elbv2 create-target-group \
  --name picpay-tg \
  --protocol HTTP \
  --port 8080 \
  --vpc-id $VPC_ID \
  --health-check-path /actuator/health \
  --region $REGION \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text)

# 3. EC2-A를 Target Group에 등록
aws elbv2 register-targets \
  --target-group-arn $TG_ARN \
  --targets Id=$EC2_A_ID \
  --region $REGION

# 4. Listener 생성 (HTTP:80 → Target Group)
aws elbv2 create-listener \
  --load-balancer-arn $ALB_ARN \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=$TG_ARN \
  --region $REGION

echo "ALB 생성 완료. 부하 테스트 후 반드시 alb-delete.sh 실행!"
echo "ALB ARN: $ALB_ARN"
```

```bash
# alb-delete.sh
ALB_ARN=$(aws elbv2 describe-load-balancers \
  --names picpay-alb \
  --query 'LoadBalancers[0].LoadBalancerArn' \
  --output text \
  --region ap-northeast-2)

aws elbv2 delete-load-balancer --load-balancer-arn $ALB_ARN --region ap-northeast-2
echo "ALB 삭제 완료."
```

---

## 6. 비용 함정 방지 체크리스트 (Section 13 추가)

```
## ⚠️ 비용 사고 방지 체크리스트

### 절대 생성 금지
- [ ] NAT Gateway (월 $43 + 데이터 처리비)
- [ ] Elastic IP (제거 완료, 스크립트로 대체)

### EC2 종료 전 확인
- [ ] ALB 삭제됨 (부하 테스트 후 즉시 실행: ./alb-delete.sh)
- [ ] 불필요한 스냅샷 없음

### 월 1회 AWS Billing 확인
- AWS Console → Billing Dashboard → Cost Explorer
- 예상 범위: 프리티어 ~$4/월, 만료 후 ~$8/월
- 이 범위 초과 시 즉시 원인 파악
```

---

## 7. 스프린트 계획 조정

### Sprint 1 추가 태스크

```
- [ ] 로컬 Docker Compose에서 메모리 프로파일링
      (5개 서비스 동시 기동 → docker stats → 전체 사용량 확인)
      EC2 t3.micro 1GB 내 수용 가능 여부 사전 판단
      실패 예상 시 → Sprint 4 시작 전 t3.small 전환 결정
```

> Sprint 1은 로컬(Docker Compose) 단계. EC2 실제 검증은 Sprint 4에서 수행.
> 로컬에서 docker stats로 메모리 총합이 750MB 초과 시 t3.small 전환 검토.

### Sprint 4 변경 태스크

```diff
- - [ ] EC2 2대 + RDS + ALB
+ - [ ] EC2 2대 + RDS (ALB 없음, 기본 구성)
+ - [ ] start-ec2.sh 작성 및 테스트
+ - [ ] alb-create.sh / alb-delete.sh 작성
  - [ ] GitHub Actions CI + SSH 배포
  - [ ] k6 부하 테스트 시 ALB 임시 생성 → 완료 후 삭제
```

---

## 8. 변경 요약

| 카테고리 | 건수 | 내용 |
|---------|:---:|------|
| 불일치 수정 | 3건 | billing 시퀀스, 서비스 참조 표, Redis 키 패턴 |
| 비용 절감 | 2건 | Elastic IP 제거($3.65/월), ALB 동적 생성 |
| 신규 추가 | 4건 | JVM 힙 설정, start-ec2.sh, alb 스크립트, 비용 체크리스트 |
| 스프린트 | 2건 | Sprint 1 메모리 검증, Sprint 4 ALB 전략 |
| **월 비용** | | 기존 ~$15 → **수정 후 ~$4 (프리티어), ~$8 (만료 후)** |
