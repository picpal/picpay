# ECR 전환 계획 및 검토

> 날짜: 2026-05-30  
> 상태: 계획 단계 (현재 CD 안정화 완료 후 진행)

---

## 전환 배경

현재 배포 방식에서 병목:
```
GitHub Actions → docker save(tar.gz) → SCP 전송 → EC2에서 docker load
```
- WAS 이미지 5개 × 평균 560MB = 약 2.8GB 무압축 / ~300MB 압축
- `docker load` 중 SSH Broken pipe 발생 (메모리 부족, 소요 시간 과다)
- 전체 배포 시간: ~20분

ECR 전환 후:
```
GitHub Actions → docker push(변경 레이어만) → EC2에서 docker pull(변경 레이어만)
```
- 첫 배포 이후: JAR 레이어(~40-85MB)만 전송
- 전체 배포 시간: ~4분 예상

---

## 비용 검토

### ECR 저장 용량 (레이어 중복 제거 적용)

| 레이어 | 압축 크기 | 저장 횟수 |
|--------|----------|---------|
| eclipse-temurin:21-jre-alpine | ~127MB | 1회 (공유) |
| addgroup/adduser | ~1MB | 1회 (공유) |
| gateway JAR | ~42MB | 1회 |
| token JAR | ~62MB | 1회 |
| notification JAR | ~70MB | 1회 |
| payment JAR | ~81MB | 1회 |
| billing JAR | ~85MB | 1회 |
| **합계** | **~468MB** | |

- ECR 프리티어: **500MB/월** (12개월)
- 사용량: **468MB** → 프리티어 이내 (여유 32MB)
- 프리티어 만료 후: $0.10/GB → 약 **$0.047/월** (약 60원)
- 전송 비용: GitHub Actions→ECR inbound 무료, ECR→EC2 동일 리전 무료

### ⚠️ lifecycle policy 미설정 시 초과 위험

`:latest` 태그로 재push 시 이전 이미지가 untagged로 잔존 → 배포 2회 시 936MB로 프리티어 초과.  
**리포지토리 생성 시 lifecycle policy 동시 설정 필수.**

```json
{
  "rules": [{
    "rulePriority": 1,
    "selection": {
      "tagStatus": "untagged",
      "countType": "sinceImagePushed",
      "countUnit": "days",
      "countNumber": 1
    },
    "action": { "type": "expire" }
  }]
}
```

---

## 안정성 체크포인트

### ✅ 문제없는 항목
- EC2-A (퍼블릭 서브넷) → ECR 접근 가능
- 미들웨어(Kafka/Redis/Zookeeper): 공개 이미지라 ECR 불필요, 기존 방식 유지
- EC2 재시작 후 이미지: 현재는 재배포 필요 → ECR 전환 후 `docker pull`로 복구 가능 (개선)

### ⚠️ 반드시 처리해야 할 항목

**1. `docker-compose.ec2-a.yml` 이미지 이름 변경**
```yaml
# 변경 전
image: picpay-gateway:latest

# 변경 후
image: ${ECR_REGISTRY}/picpay-gateway:latest
```
ECR_REGISTRY 환경변수: `050721760781.dkr.ecr.ap-northeast-2.amazonaws.com`

**2. EC2-A에 IAM Instance Profile 부착 필요**
- 권한: `AmazonEC2ContainerRegistryReadOnly`
- 없으면 `aws ecr get-login-password` 실패 → docker pull 불가

**3. GitHub Actions AWS 자격증명 추가**
- OIDC 방식 (권장): IAM Role + GitHub OIDC Provider (키 관리 불필요)
- IAM User 방식 (간단): AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY를 GitHub Secrets에 추가
- → IAM User 방식으로 진행 예정 (설정 단순)

**4. ECR 인증 토큰 갱신 (12시간 유효)**

배포 시 EC2-A에서 자동 갱신:
```bash
aws ecr get-login-password --region ap-northeast-2 | \
  docker login --username AWS --password-stdin \
  050721760781.dkr.ecr.ap-northeast-2.amazonaws.com
```

**5. plain JAR 문제 먼저 해결 필요**

`build/libs/*.jar` glob이 fat JAR + plain JAR 두 개 매칭.  
→ 각 서비스 `build.gradle`에 `jar { enabled = false }` 추가 후 ECR 전환.

---

## cd.yml 변경 범위

| 단계 | 현재 | ECR 전환 후 |
|------|------|------------|
| Build WAS images | `docker save` → tar.gz | `docker push` → ECR |
| Copy WAS images to EC2-A | SCP tar.gz (제거) | — |
| Deploy WAS on EC2-A | `docker load` + compose up | ECR login + `docker pull` + compose up |
| 미들웨어 관련 전체 | 변경 없음 | 변경 없음 |

새로 추가되는 Actions 스텝:
```yaml
- uses: aws-actions/configure-aws-credentials@v4
- uses: aws-actions/amazon-ecr-login@v2
```

---

## 진행 순서 (TODO)

- [ ] **Step 1**: 현재 CD 안정화 확인 (SSH Broken pipe, Kafka OOM 수정 결과 확인)
- [ ] **Step 2**: plain JAR 제거 (`jar { enabled = false }`) → 이미지 크기 최적화
- [ ] **Step 3**: ECR 리포지토리 5개 생성 + lifecycle policy 설정
- [ ] **Step 4**: IAM User(ECR push 권한) 생성 → GitHub Secrets 등록
- [ ] **Step 5**: EC2-A IAM Instance Profile 생성 + 부착 (ECR pull 권한)
- [ ] **Step 6**: `docker-compose.ec2-a.yml` 이미지 이름 변경
- [ ] **Step 7**: `cd.yml` 변경 (ECR push + pull 방식으로)
- [ ] **Step 8**: 배포 테스트 및 속도 측정
