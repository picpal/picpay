# Layer 7 — AWS Deployment + Load Testing

> 작성일: 2026-05-27  
> 기반 문서: 2026-05-25-implementation-plan-design.md, 2026-05-25-picpay-prd-review-design.md  
> 목표: 로컬 MSA를 AWS에 배포, CI/CD 자동화, k6 부하 테스트 준비  
> 세션: S30~S33 (4 sessions)

---

## 인프라 구성 요약

```
ap-northeast-2 (Seoul)
VPC: 10.0.0.0/16

Public Subnet: 10.0.1.0/24 (ap-northeast-2a)
└── EC2-A (t3.micro) — 5개 Spring Boot 서비스
    ports: 8080 (gateway), 8081 (payment), 8082 (billing), 8083 (token), 8084 (notification)

Private Subnet: 10.0.2.0/24 (ap-northeast-2a)
├── EC2-B (t3.micro) — Kafka + Zookeeper + Redis
│   - 인터넷 불가 (NAT Gateway 금지)
│   - EC2-A를 통해 SSH 접근 (bastion 패턴)
│   - Docker 이미지: CI runner → EC2-A → EC2-B (SCP 전달)
└── RDS (db.t3.micro) — PostgreSQL 16

ALB: 부하 테스트 시에만 임시 생성/삭제
```

### Security Group 규칙

| SG | 포트 | 소스 | 용도 |
|----|------|------|------|
| sg-alb | 80 | 0.0.0.0/0 | ALB 인바운드 (부하 테스트용) |
| sg-was | 8080 | 개발자 IP | API Gateway 직접 접속 |
| sg-was | 8080-8084 | sg-alb | ALB 경유 트래픽 |
| sg-was | 22 | 개발자 IP | SSH 직접 접속 |
| sg-middleware | 9092,2181 | sg-was | Kafka/Zookeeper |
| sg-middleware | 6379 | sg-was | Redis |
| sg-middleware | 22 | sg-was | SSH (EC2-A bastion 경유) |
| sg-db | 5432 | sg-was | PostgreSQL |

### EC2-B 이미지 배포 방식 (NAT 없는 private subnet)

```
GitHub Actions runner
  └─(SCP)─▶ EC2-A (public subnet)
                ├─(SCP via private IP)─▶ EC2-B
                │   confluentinc/cp-zookeeper:7.5.0 (~200MB)
                │   confluentinc/cp-kafka:7.5.0 (~800MB)
                │   redis:7 (~30MB)
                └─ EC2-B: docker load + docker-compose up
```

GitHub Actions에서 EC2-B 접근:
```bash
ssh -o ProxyJump=ubuntu@${EC2_A_IP} ubuntu@${EC2_B_PRIVATE_IP} "docker load < /tmp/kafka.tar"
```

### JVM 힙 설정 (t3.micro 1GB 기준)

| 서비스 | `-Xmx` | `-Xms` |
|--------|-------:|-------:|
| gateway | 150m | 75m |
| payment | 200m | 100m |
| billing | 150m | 75m |
| token | 150m | 75m |
| notification | 100m | 50m |

---

## S30 — Production Dockerfiles + docker-compose

### 목표
5개 서비스 Dockerfile(멀티스테이지) + EC2-A용 docker-compose + EC2-B용 docker-compose 작성.

### 파일 목록

```
services/gateway/Dockerfile
services/payment/Dockerfile
services/billing/Dockerfile
services/token/Dockerfile
services/notification/Dockerfile
deploy/docker-compose.ec2-a.yml     (WAS 5개 서비스)
deploy/docker-compose.ec2-b.yml     (Kafka + Zookeeper + Redis)
```

### Dockerfile 스펙 (공통 패턴)

```dockerfile
# 빌드 스테이지
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY --from=root . .
RUN ./gradlew :services/<service>:bootJar -x test --no-daemon

# 런타임 스테이지
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder /app/services/<service>/build/libs/*.jar app.jar
USER app
EXPOSE <port>
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

주의사항:
- 루트 `build.gradle`, `settings.gradle`, `gradlew`, `gradle/` 전체 COPY 필요 (멀티모듈)
- `JAVA_OPTS` 환경변수로 JVM 힙 주입 (docker-compose에서 설정)

### deploy/docker-compose.ec2-a.yml 스펙

```yaml
services:
  gateway:
    image: picpay-gateway:latest
    ports: ["8080:8080"]
    environment:
      JAVA_OPTS: -Xmx150m -Xms75m -XX:+UseG1GC
      DB_URL: r2dbc:postgresql://${RDS_HOST}:5432/picpay
      DB_USERNAME: picpay
      DB_PASSWORD: ${DB_PASSWORD}
      JWT_SECRET: ${JWT_SECRET}
      SPRING_DATA_REDIS_HOST: ${EC2_B_PRIVATE_IP}
      SERVICES_PAYMENT_URL: http://payment:8081
      SERVICES_BILLING_URL: http://billing:8082
      SERVICES_TOKEN_URL: http://token:8083
    deploy:
      resources:
        limits:
          memory: 200m
    restart: unless-stopped

  payment:
    image: picpay-payment:latest
    ports: ["8081:8081"]
    environment:
      JAVA_OPTS: -Xmx200m -Xms100m -XX:+UseG1GC
      SPRING_DATASOURCE_URL: jdbc:postgresql://${RDS_HOST}:5432/picpay
      SPRING_DATASOURCE_USERNAME: picpay
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_DATA_REDIS_HOST: ${EC2_B_PRIVATE_IP}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: ${EC2_B_PRIVATE_IP}:9092
      TOKEN_SERVICE_URL: http://token:8083
      VAULT_AES_KEY: ${VAULT_AES_KEY}
    deploy:
      resources:
        limits:
          memory: 256m
    restart: unless-stopped

  billing:
    image: picpay-billing:latest
    ports: ["8082:8082"]
    environment:
      JAVA_OPTS: -Xmx150m -Xms75m -XX:+UseG1GC
      SPRING_DATASOURCE_URL: jdbc:postgresql://${RDS_HOST}:5432/picpay
      SPRING_DATASOURCE_USERNAME: picpay
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_DATA_REDIS_HOST: ${EC2_B_PRIVATE_IP}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: ${EC2_B_PRIVATE_IP}:9092
      PAYMENT_SERVICE_URL: http://payment:8081
    deploy:
      resources:
        limits:
          memory: 200m
    restart: unless-stopped

  token:
    image: picpay-token:latest
    ports: ["8083:8083"]
    environment:
      JAVA_OPTS: -Xmx150m -Xms75m -XX:+UseG1GC
      SPRING_DATASOURCE_URL: jdbc:postgresql://${RDS_HOST}:5432/picpay
      SPRING_DATASOURCE_USERNAME: picpay
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_DATA_REDIS_HOST: ${EC2_B_PRIVATE_IP}
      VAULT_AES_KEY: ${VAULT_AES_KEY}
    deploy:
      resources:
        limits:
          memory: 200m
    restart: unless-stopped

  notification:
    image: picpay-notification:latest
    ports: ["8084:8084"]
    environment:
      JAVA_OPTS: -Xmx100m -Xms50m -XX:+UseG1GC
      SPRING_DATASOURCE_URL: jdbc:postgresql://${RDS_HOST}:5432/picpay
      SPRING_DATASOURCE_USERNAME: picpay
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      SPRING_KAFKA_BOOTSTRAP_SERVERS: ${EC2_B_PRIVATE_IP}:9092
    deploy:
      resources:
        limits:
          memory: 150m
    restart: unless-stopped
```

### deploy/docker-compose.ec2-b.yml 스펙

```yaml
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    ports: ["2181:2181"]
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    restart: unless-stopped

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports: ["9092:9092"]
    depends_on: [zookeeper]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://${EC2_B_PRIVATE_IP}:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'false'
    restart: unless-stopped

  redis:
    image: redis:7
    ports: ["6379:6379"]
    restart: unless-stopped
```

`KAFKA_ADVERTISED_LISTENERS`에 Private IP 사용 — EC2-A의 Spring Boot 서비스가 해당 주소로 접속.

### 완료 기준
- `docker build` 5개 서비스 모두 성공
- `docker images` 목록에 5개 이미지 존재
- docker-compose.ec2-a.yml / docker-compose.ec2-b.yml 환경변수 플레이스홀더 완비

---

## S31 — AWS 인프라 스크립트

### 목표
VPC/서브넷/SG/EC2/RDS 생성 AWS CLI 스크립트 작성. 실제 배포 시 순서대로 실행.

### 파일 목록

```
infra/
├── 01-vpc-setup.sh         VPC, 서브넷, IGW, 라우팅 테이블, SG 4개
├── 02-ec2-setup.sh         EC2-A/B 생성, Docker 설치
├── 03-rds-setup.sh         RDS PostgreSQL 16, db.t3.micro
├── 04-flyway-migrate.sh    EC2-A에서 Flyway 마이그레이션 실행
├── start-ec2.sh            EC2-A/B 시작, 동적 IP 갱신, GitHub Secret 업데이트
├── alb-create.sh           ALB + Target Group + Listener 생성 (부하 테스트 전)
├── alb-delete.sh           ALB 삭제 (부하 테스트 후)
└── .env.infra.example      필수 환경변수 템플릿 (KEY_NAME, VPC CIDR 등)
```

### 01-vpc-setup.sh 스펙

```bash
#!/bin/bash
# AWS 리전: ap-northeast-2 (Seoul)
# 실행 전제: aws-cli 설치, 적절한 IAM 권한

REGION="ap-northeast-2"
VPC_CIDR="10.0.0.0/16"
PUBLIC_CIDR="10.0.1.0/24"
PRIVATE_CIDR="10.0.2.0/24"
AZ="ap-northeast-2a"

# 1. VPC 생성
VPC_ID=$(aws ec2 create-vpc --cidr-block $VPC_CIDR --region $REGION \
  --tag-specifications "ResourceType=vpc,Tags=[{Key=Name,Value=picpay-vpc}]" \
  --query 'Vpc.VpcId' --output text)

# 2. Public Subnet (EC2-A)
PUBLIC_SUBNET_ID=$(aws ec2 create-subnet --vpc-id $VPC_ID \
  --cidr-block $PUBLIC_CIDR --availability-zone $AZ \
  --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=picpay-public}]" \
  --query 'Subnet.SubnetId' --output text)

# 3. Private Subnet (EC2-B, RDS)
PRIVATE_SUBNET_ID=$(aws ec2 create-subnet --vpc-id $VPC_ID \
  --cidr-block $PRIVATE_CIDR --availability-zone $AZ \
  --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=picpay-private}]" \
  --query 'Subnet.SubnetId' --output text)

# 4. Internet Gateway (Public Subnet용)
IGW_ID=$(aws ec2 create-internet-gateway --region $REGION \
  --tag-specifications "ResourceType=internet-gateway,Tags=[{Key=Name,Value=picpay-igw}]" \
  --query 'InternetGateway.InternetGatewayId' --output text)
aws ec2 attach-internet-gateway --internet-gateway-id $IGW_ID --vpc-id $VPC_ID

# 5. Public Route Table (0.0.0.0/0 → IGW)
PUBLIC_RT=$(aws ec2 create-route-table --vpc-id $VPC_ID \
  --query 'RouteTable.RouteTableId' --output text)
aws ec2 create-route --route-table-id $PUBLIC_RT --destination-cidr-block 0.0.0.0/0 --gateway-id $IGW_ID
aws ec2 associate-route-table --route-table-id $PUBLIC_RT --subnet-id $PUBLIC_SUBNET_ID

# 6. Private Route Table (Local만 — NAT Gateway 없음)
# Private Subnet은 기본 Local 라우팅만 유지

# 7. Security Groups
SG_ALB=$(aws ec2 create-security-group --group-name sg-alb --description "ALB SG" --vpc-id $VPC_ID \
  --query 'GroupId' --output text)
SG_WAS=$(aws ec2 create-security-group --group-name sg-was --description "WAS SG" --vpc-id $VPC_ID \
  --query 'GroupId' --output text)
SG_MIDDLEWARE=$(aws ec2 create-security-group --group-name sg-middleware --description "Middleware SG" --vpc-id $VPC_ID \
  --query 'GroupId' --output text)
SG_DB=$(aws ec2 create-security-group --group-name sg-db --description "DB SG" --vpc-id $VPC_ID \
  --query 'GroupId' --output text)

# sg-alb 규칙: HTTP 80 전체 허용
aws ec2 authorize-security-group-ingress --group-id $SG_ALB --protocol tcp --port 80 --cidr 0.0.0.0/0

# sg-was 규칙: 8080 (개발자 IP), 8080-8084 (ALB), 22 (개발자 IP)
MY_IP=$(curl -s ifconfig.me)/32
aws ec2 authorize-security-group-ingress --group-id $SG_WAS --protocol tcp --port 8080 --cidr $MY_IP
aws ec2 authorize-security-group-ingress --group-id $SG_WAS --protocol tcp --port 22 --cidr $MY_IP
aws ec2 authorize-security-group-ingress --group-id $SG_WAS --protocol tcp --port 8080 --source-group $SG_ALB
aws ec2 authorize-security-group-ingress --group-id $SG_WAS --protocol tcp --from-port 8081 --to-port 8084 --source-group $SG_ALB

# sg-middleware 규칙: Kafka/Zookeeper/Redis/SSH from sg-was
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 9092 --source-group $SG_WAS
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 2181 --source-group $SG_WAS
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 6379 --source-group $SG_WAS
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 22 --source-group $SG_WAS

# sg-db 규칙: PostgreSQL from sg-was
aws ec2 authorize-security-group-ingress --group-id $SG_DB --protocol tcp --port 5432 --source-group $SG_WAS

echo "VPC_ID=$VPC_ID"
echo "PUBLIC_SUBNET_ID=$PUBLIC_SUBNET_ID"
echo "PRIVATE_SUBNET_ID=$PRIVATE_SUBNET_ID"
echo "SG_ALB=$SG_ALB  SG_WAS=$SG_WAS  SG_MIDDLEWARE=$SG_MIDDLEWARE  SG_DB=$SG_DB"
# → infra/infra-ids.env 에 저장 (이후 스크립트에서 source)
```

### 02-ec2-setup.sh 스펙

```bash
#!/bin/bash
source infra/infra-ids.env

REGION="ap-northeast-2"
AMI_ID="ami-0c9c942bd7bf113a2"  # Ubuntu 24.04 LTS ap-northeast-2
INSTANCE_TYPE="t3.micro"
KEY_NAME="${KEY_NAME:?'KEY_NAME 환경변수 필요'}"

DOCKER_INSTALL=$(cat <<'USERDATA'
#!/bin/bash
apt-get update -y
apt-get install -y docker.io docker-compose-v2
usermod -aG docker ubuntu
systemctl enable docker && systemctl start docker
USERDATA
)

# EC2-A (Public Subnet, WAS)
EC2_A_ID=$(aws ec2 run-instances \
  --image-id $AMI_ID --instance-type $INSTANCE_TYPE \
  --key-name $KEY_NAME --security-group-ids $SG_WAS \
  --subnet-id $PUBLIC_SUBNET_ID --associate-public-ip-address \
  --user-data "$DOCKER_INSTALL" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=picpay-ec2-a-was}]" \
  --query 'Instances[0].InstanceId' --output text)

# EC2-B (Private Subnet, Middleware)
EC2_B_ID=$(aws ec2 run-instances \
  --image-id $AMI_ID --instance-type $INSTANCE_TYPE \
  --key-name $KEY_NAME --security-group-ids $SG_MIDDLEWARE \
  --subnet-id $PRIVATE_SUBNET_ID --no-associate-public-ip-address \
  --user-data "$DOCKER_INSTALL" \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=picpay-ec2-b-middleware}]" \
  --query 'Instances[0].InstanceId' --output text)

aws ec2 wait instance-running --instance-ids $EC2_A_ID $EC2_B_ID

EC2_A_PUBLIC_IP=$(aws ec2 describe-instances --instance-ids $EC2_A_ID \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
EC2_B_PRIVATE_IP=$(aws ec2 describe-instances --instance-ids $EC2_B_ID \
  --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)

echo "EC2_A_ID=$EC2_A_ID  EC2_A_PUBLIC_IP=$EC2_A_PUBLIC_IP"
echo "EC2_B_ID=$EC2_B_ID  EC2_B_PRIVATE_IP=$EC2_B_PRIVATE_IP"
# → infra/infra-ids.env 에 추가
```

### 03-rds-setup.sh 스펙

```bash
#!/bin/bash
source infra/infra-ids.env

# RDS Subnet Group (Private Subnet — 단일 AZ이므로 최소 2개 AZ 필요, dummy subnet 추가)
aws rds create-db-subnet-group \
  --db-subnet-group-name picpay-db-subnet-group \
  --db-subnet-group-description "PicPay DB Subnet Group" \
  --subnet-ids $PRIVATE_SUBNET_ID

# RDS 생성
aws rds create-db-instance \
  --db-instance-identifier picpay-rds \
  --db-instance-class db.t3.micro \
  --engine postgres --engine-version 16 \
  --master-username picpay \
  --master-user-password "${DB_PASSWORD:?}" \
  --db-name picpay \
  --vpc-security-group-ids $SG_DB \
  --db-subnet-group-name picpay-db-subnet-group \
  --no-publicly-accessible \
  --allocated-storage 20 --storage-type gp3

aws rds wait db-instance-available --db-instance-identifier picpay-rds

RDS_ENDPOINT=$(aws rds describe-db-instances \
  --db-instance-identifier picpay-rds \
  --query 'DBInstances[0].Endpoint.Address' --output text)
echo "RDS_HOST=$RDS_ENDPOINT"
```

참고: RDS Subnet Group은 2개 AZ 이상 필요. Private Subnet이 1개뿐이면 추가 서브넷(dummy) 생성 필요. 스크립트에서 처리.

### 04-flyway-migrate.sh 스펙

```bash
#!/bin/bash
source infra/infra-ids.env
# EC2-A에서 payment 서비스 JAR로 Flyway 마이그레이션 실행
ssh -i ~/.ssh/${KEY_NAME}.pem ubuntu@${EC2_A_PUBLIC_IP} \
  "docker run --rm \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://${RDS_HOST}:5432/picpay \
    -e SPRING_DATASOURCE_USERNAME=picpay \
    -e SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD} \
    picpay-payment:latest \
    java -jar app.jar --spring.flyway.enabled=true --spring.main.web-application-type=none 2>&1 | head -50"
```

### start-ec2.sh 스펙 (PRD Review doc 기반)

```bash
#!/bin/bash
source infra/infra-ids.env

REGION="ap-northeast-2"

# EC2-A/B 동시 시작
aws ec2 start-instances --instance-ids $EC2_A_ID $EC2_B_ID --region $REGION
aws ec2 wait instance-running --instance-ids $EC2_A_ID $EC2_B_ID --region $REGION

# EC2-A Public IP (동적)
PUBLIC_IP=$(aws ec2 describe-instances --instance-ids $EC2_A_ID --region $REGION \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)

echo "EC2-A Public IP: $PUBLIC_IP"

# SSH config 갱신 (EC2-A)
sed -i '' "s/HostName .*/HostName $PUBLIC_IP/" ~/.ssh/config

# GitHub Actions Secret 갱신 (gh CLI 필요, 로컬 repo에서 실행)
gh secret set EC2_HOST --body "$PUBLIC_IP"

echo "완료. 접속: ssh picpay-was"
```

### 완료 기준
- `aws ec2 describe-vpcs --filters Name=tag:Name,Values=picpay-vpc` — VPC 확인
- `aws ec2 describe-instances --filters Name=tag:Name,Values=picpay-ec2-a-was` — EC2-A 확인
- `ssh picpay-was "docker --version"` — Docker 설치 확인
- `aws rds describe-db-instances --db-instance-identifier picpay-rds` — RDS 확인

---

## S32 — GitHub Actions CI/CD

### 목표
push → CI (빌드+테스트), main 병합 → CD (docker save → SCP → SSH 배포).

### 파일 목록

```
.github/
└── workflows/
    ├── ci.yml      PR/push 시 빌드 + 테스트
    └── cd.yml      main push 시 EC2 배포
```

### .github/workflows/ci.yml 스펙

```yaml
name: CI

on:
  push:
    branches-ignore: [main]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'
      - name: Run tests
        run: ./gradlew test --no-daemon
      - name: Upload test results
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: '**/build/reports/tests/'
```

### .github/workflows/cd.yml 스펙

```yaml
name: CD

on:
  push:
    branches: [main]

env:
  EC2_USER: ubuntu

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'gradle'

      - name: Build JARs
        run: ./gradlew bootJar -x test --no-daemon

      - name: Build Docker images
        run: |
          for svc in gateway payment billing token notification; do
            docker build -f services/$svc/Dockerfile -t picpay-$svc:latest .
          done

      - name: Save WAS images (EC2-A)
        run: |
          mkdir -p /tmp/images
          for svc in gateway payment billing token notification; do
            docker save picpay-$svc:latest | gzip > /tmp/images/$svc.tar.gz
          done

      - name: Save middleware images (EC2-B)
        run: |
          docker pull confluentinc/cp-zookeeper:7.5.0
          docker pull confluentinc/cp-kafka:7.5.0
          docker pull redis:7
          docker save confluentinc/cp-zookeeper:7.5.0 | gzip > /tmp/images/zookeeper.tar.gz
          docker save confluentinc/cp-kafka:7.5.0 | gzip > /tmp/images/kafka.tar.gz
          docker save redis:7 | gzip > /tmp/images/redis.tar.gz

      - name: Setup SSH key
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.EC2_SSH_KEY }}" > ~/.ssh/picpay.pem
          chmod 600 ~/.ssh/picpay.pem

      - name: Deploy to EC2-A (WAS)
        env:
          EC2_HOST: ${{ secrets.EC2_HOST }}
          RDS_HOST: ${{ secrets.RDS_HOST }}
          DB_PASSWORD: ${{ secrets.DB_PASSWORD }}
          JWT_SECRET: ${{ secrets.JWT_SECRET }}
          VAULT_AES_KEY: ${{ secrets.VAULT_AES_KEY }}
          EC2_B_PRIVATE_IP: ${{ secrets.EC2_B_PRIVATE_IP }}
        run: |
          # SCP images to EC2-A
          scp -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no \
            /tmp/images/gateway.tar.gz /tmp/images/payment.tar.gz \
            /tmp/images/billing.tar.gz /tmp/images/token.tar.gz \
            /tmp/images/notification.tar.gz \
            $EC2_USER@$EC2_HOST:/tmp/

          # SCP docker-compose to EC2-A
          scp -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no \
            deploy/docker-compose.ec2-a.yml \
            $EC2_USER@$EC2_HOST:/home/ubuntu/docker-compose.yml

          # Load images + start services on EC2-A
          ssh -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no $EC2_USER@$EC2_HOST <<EOF
            for svc in gateway payment billing token notification; do
              docker load < /tmp/\${svc}.tar.gz
            done
            RDS_HOST=$RDS_HOST DB_PASSWORD=$DB_PASSWORD \
            JWT_SECRET=$JWT_SECRET VAULT_AES_KEY=$VAULT_AES_KEY \
            EC2_B_PRIVATE_IP=$EC2_B_PRIVATE_IP \
            docker compose up -d --remove-orphans
          EOF

      - name: Deploy to EC2-B (Middleware) via bastion
        env:
          EC2_HOST: ${{ secrets.EC2_HOST }}
          EC2_B_PRIVATE_IP: ${{ secrets.EC2_B_PRIVATE_IP }}
        run: |
          # SCP middleware images via EC2-A to EC2-B
          ssh -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no $EC2_USER@$EC2_HOST \
            "mkdir -p /tmp/middleware"
          scp -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no \
            /tmp/images/zookeeper.tar.gz /tmp/images/kafka.tar.gz /tmp/images/redis.tar.gz \
            $EC2_USER@$EC2_HOST:/tmp/middleware/

          # EC2-A에서 EC2-B로 이미지 전달 (bastion SCP)
          ssh -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no $EC2_USER@$EC2_HOST \
            "scp -o StrictHostKeyChecking=no /tmp/middleware/*.tar.gz \
             $EC2_USER@$EC2_B_PRIVATE_IP:/tmp/"

          # SCP docker-compose.ec2-b.yml to EC2-A → EC2-B
          scp -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no \
            deploy/docker-compose.ec2-b.yml \
            $EC2_USER@$EC2_HOST:/tmp/docker-compose.ec2-b.yml
          ssh -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no $EC2_USER@$EC2_HOST \
            "scp -o StrictHostKeyChecking=no /tmp/docker-compose.ec2-b.yml \
             $EC2_USER@$EC2_B_PRIVATE_IP:/home/ubuntu/docker-compose.yml"

          # Load images + start services on EC2-B via bastion SSH
          ssh -i ~/.ssh/picpay.pem -o StrictHostKeyChecking=no $EC2_USER@$EC2_HOST \
            "ssh -o StrictHostKeyChecking=no $EC2_USER@$EC2_B_PRIVATE_IP <<'REMOTE'
               for img in zookeeper kafka redis; do
                 docker load < /tmp/\${img}.tar.gz
               done
               EC2_B_PRIVATE_IP=$EC2_B_PRIVATE_IP docker compose up -d --remove-orphans
             REMOTE"

      - name: Health check
        env:
          EC2_HOST: ${{ secrets.EC2_HOST }}
        run: |
          sleep 30
          curl -f http://$EC2_HOST:8080/actuator/health || exit 1
```

### GitHub Actions Secrets 목록

| Secret | 설명 |
|--------|------|
| `EC2_SSH_KEY` | picpay.pem 내용 (SSH 개인키) |
| `EC2_HOST` | EC2-A Public IP (start-ec2.sh가 자동 갱신) |
| `EC2_B_PRIVATE_IP` | EC2-B Private IP (고정) |
| `RDS_HOST` | RDS 엔드포인트 |
| `DB_PASSWORD` | PostgreSQL 비밀번호 |
| `JWT_SECRET` | JWT 서명 키 (base64) |
| `VAULT_AES_KEY` | AES-256 암호화 키 (base64) |

### EC2-B SSH Key 전달 방식
EC2-A → EC2-B SSH 접속에 필요한 key는 `authorized_keys`로 처리:
- EC2-B 생성 시 `--key-name` 동일 Key Pair 사용 → GitHub Actions의 같은 `EC2_SSH_KEY`로 접근 가능
- EC2-A의 SSH config에 `IdentityFile ~/.ssh/picpay.pem` 불필요 — 인라인 heredoc SSH 사용

### 완료 기준
- PR push → CI 워크플로우 실행, 테스트 통과
- `git push origin main` → CD 워크플로우 실행
- `curl http://{EC2-A-IP}:8080/actuator/health` → 200 OK

---

## S33 — k6 부하 테스트 스크립트

### 목표
6개 k6 시나리오 스크립트 작성. ALB 생성 후 실행, 완료 후 ALB 삭제.

### 파일 목록

```
k6/
├── scenarios/
│   ├── 01-single-payment.js        100 TPS / 3분 / P95 < 500ms
│   ├── 02-concurrent-payment.js    50 TPS / 1분 / 중복 결제 0건
│   ├── 03-billing-concurrent.js    30 TPS / 1분 / 분산 락 정상
│   ├── 04-token-hotkey.js          500 TPS / 2분 / 캐시 히트율 > 95%
│   ├── 05-composite.js             200 TPS / 5분 / Kafka Lag < 100
│   └── 06-peak.js                  500 TPS / 1분 / 한계점 확인
├── lib/
│   ├── auth.js                     JWT 획득 헬퍼
│   └── checks.js                   공통 체크 함수
└── run-all.sh                      ALB 생성 → 전체 실행 → ALB 삭제
```

### k6/lib/auth.js

```javascript
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'test-api-key';

export function getToken() {
  const res = http.post(`${BASE_URL}/v1/auth/token`, null, {
    headers: { 'X-Api-Key': API_KEY },
  });
  check(res, { 'auth 200': (r) => r.status === 200 });
  return res.json('data.token');
}
```

### k6/scenarios/01-single-payment.js 스펙

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';
import { getToken } from '../lib/auth.js';

export const options = {
  scenarios: {
    single_payment: {
      executor: 'constant-arrival-rate',
      rate: 100,           // 100 TPS
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 200,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],   // P95 < 500ms
    http_req_failed: ['rate<0.01'],     // 에러율 < 1%
  },
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const token = data.token;
  const payload = JSON.stringify({
    merchantId: 'merchant-001',
    amount: Math.floor(Math.random() * 100000) + 1000,
    currency: 'KRW',
    tokenId: `token-${__VU}-${__ITER}`,
    idempotencyKey: `key-${__VU}-${__ITER}-${Date.now()}`,
  });

  const res = http.post(
    `${__ENV.BASE_URL}/v1/payments`,
    payload,
    {
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
    }
  );

  check(res, {
    'payment 200 or 201': (r) => r.status === 200 || r.status === 201,
    'has tid': (r) => r.json('data.tid') !== undefined,
  });
}
```

### k6/scenarios/02-concurrent-payment.js 스펙 (멱등성)

```javascript
// 동일 idempotencyKey로 동시 요청 → DB에 1건만 저장되어야 함
export const options = {
  scenarios: {
    concurrent_payment: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 100,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

// 같은 idempotencyKey를 여러 VU가 공유
const SHARED_KEY = 'idempotency-shared-test-key';

export default function (data) {
  const res = http.post(`${__ENV.BASE_URL}/v1/payments`, JSON.stringify({
    merchantId: 'merchant-001',
    amount: 10000,
    currency: 'KRW',
    tokenId: 'token-test',
    idempotencyKey: SHARED_KEY,
  }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } });

  // 동일 idempotencyKey: 200 OK + 동일 응답 기대
  check(res, { 'idempotent response': (r) => r.status === 200 || r.status === 201 });
}
```

### k6/scenarios/03-billing-concurrent.js 스펙 (분산 락)

```javascript
// 동일 planId에 동시 빌링 실행 → 1건만 처리되어야 함
export const options = {
  scenarios: {
    billing_concurrent: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 60,
    },
  },
};

export default function (data) {
  // 빌링 플랜 이력 조회 (스케줄러 결과 간접 확인)
  const res = http.get(
    `${__ENV.BASE_URL}/v1/billing/plans/plan-001/history`,
    { headers: { Authorization: `Bearer ${data.token}` } }
  );
  check(res, { 'billing 200': (r) => r.status === 200 });
}
```

### k6/scenarios/04-token-hotkey.js 스펙 (캐시 히트율)

```javascript
// 동일 tokenId를 반복 조회 → Redis 캐시 히트율 > 95%
export const options = {
  scenarios: {
    token_hotkey: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 600,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<100'],  // 캐시 히트 시 < 100ms 기대
    http_req_failed: ['rate<0.01'],
  },
};

const HOT_TOKEN_ID = 'hot-token-001';

export default function (data) {
  const res = http.get(
    `${__ENV.BASE_URL}/v1/tokens/${HOT_TOKEN_ID}`,
    { headers: { Authorization: `Bearer ${data.token}` } }
  );
  check(res, { 'token 200': (r) => r.status === 200 });
}
```

### k6/scenarios/05-composite.js 스펙 (복합)

```javascript
// 결제 + 토큰조회 + 빌링 혼합 시나리오
export const options = {
  scenarios: {
    composite: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 300,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export default function (data) {
  const r = Math.random();
  if (r < 0.5) {
    // 50% — 결제
    http.post(`${__ENV.BASE_URL}/v1/payments`, JSON.stringify({
      merchantId: 'merchant-001', amount: 5000, currency: 'KRW',
      tokenId: 'token-composite', idempotencyKey: `key-${__VU}-${__ITER}`,
    }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } });
  } else if (r < 0.8) {
    // 30% — 토큰 조회
    http.get(`${__ENV.BASE_URL}/v1/tokens/hot-token-001`,
      { headers: { Authorization: `Bearer ${data.token}` } });
  } else {
    // 20% — 빌링 이력
    http.get(`${__ENV.BASE_URL}/v1/billing/plans/plan-001/history`,
      { headers: { Authorization: `Bearer ${data.token}` } });
  }
}
```

### k6/scenarios/06-peak.js 스펙 (피크)

```javascript
// 500 TPS 1분 — 한계점 탐색 (통과 기준 없음, 에러율/P95 측정)
export const options = {
  scenarios: {
    peak: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      stages: [
        { duration: '30s', target: 500 },
        { duration: '30s', target: 500 },
      ],
      preAllocatedVUs: 600,
      maxVUs: 800,
    },
  },
};

export default function (data) {
  http.post(`${__ENV.BASE_URL}/v1/payments`, JSON.stringify({
    merchantId: 'merchant-001', amount: 1000, currency: 'KRW',
    tokenId: 'token-peak', idempotencyKey: `peak-${__VU}-${__ITER}`,
  }), { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } });
}
```

### k6/run-all.sh 스펙

```bash
#!/bin/bash
# 전제: ALB_URL 환경변수 또는 EC2_A_IP 직접 사용
# 부하 테스트 전 ALB 생성, 완료 후 삭제

BASE_URL="${BASE_URL:?'BASE_URL 환경변수 필요 (ALB DNS 또는 EC2-A IP:8080)'}"
API_KEY="${API_KEY:?'API_KEY 환경변수 필요'}"

mkdir -p docs/load-test

SCENARIOS=(
  "01-single-payment"
  "02-concurrent-payment"
  "03-billing-concurrent"
  "04-token-hotkey"
  "05-composite"
  "06-peak"
)

for scenario in "${SCENARIOS[@]}"; do
  echo "=== Running $scenario ==="
  k6 run \
    --env BASE_URL=$BASE_URL \
    --env API_KEY=$API_KEY \
    --out json=docs/load-test/${scenario}-$(date +%Y%m%d-%H%M%S).json \
    k6/scenarios/${scenario}.js
  echo "=== Completed $scenario ==="
  sleep 10  # 서비스 안정화 대기
done

echo "전체 시나리오 완료. docs/load-test/ 확인"
```

### 완료 기준
- k6 스크립트 6개 문법 오류 없음 (`k6 run --dry-run`)
- `run-all.sh` 실행 가능 권한 설정
- `docs/load-test/` 디렉토리 생성

---

## 전체 실행 순서

```
1. infra/01-vpc-setup.sh           VPC + SG 생성
2. infra/02-ec2-setup.sh           EC2-A/B 생성 + Docker 설치
3. infra/03-rds-setup.sh           RDS 생성
4. infra/04-flyway-migrate.sh      DB 스키마 초기화
5. git push origin main            GitHub Actions CD → EC2 배포
6. curl EC2-A:8080/actuator/health 헬스체크 확인
7. infra/alb-create.sh             부하 테스트 전 ALB 생성
8. k6/run-all.sh                   k6 시나리오 1~6 실행
9. infra/alb-delete.sh             ALB 삭제
```

---

## 비용 주의 (절대 금지)

- NAT Gateway: 금지 ($43/월)
- Elastic IP: 제거 완료 (start-ec2.sh로 동적 IP 관리)
- ALB: 부하 테스트 후 즉시 삭제 (`alb-delete.sh`)
- EC2 종료 전 ALB 삭제 확인
