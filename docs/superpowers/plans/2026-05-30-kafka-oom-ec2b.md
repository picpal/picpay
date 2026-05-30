# Kafka OOM on EC2-B (t3.micro)

> 날짜: 2026-05-30

## 오류

```
Container ubuntu-token-1  Error
dependency failed to start: container ubuntu-token-1 is unhealthy
```

EC2-B에서 Kafka 컨테이너가 계속 재시작(`Restarting (1)`).

## 원인 분석

Kafka 컨테이너 로그:
```
os.memory.free=11MB
Memory: physical 963376k(72672k free)
```

EC2-B t3.micro 1GB RAM에서 JVM 힙 기본값이 너무 큼:
- Zookeeper: 기본 힙 ~512MB
- Kafka: 기본 힙 ~1GB
- Redis: ~50MB

합계 ~1.56GB > 1GB RAM → Kafka JVM OOM → 재시작 반복

token 서비스는 Kafka에 의존하므로 헬스체크 실패 → `ubuntu-token-1 is unhealthy`

## 시도한 방법

### Fix: JVM 힙 제한 추가

`deploy/docker-compose.ec2-b.yml`에 환경변수 추가:
```yaml
zookeeper:
  environment:
    ZOOKEEPER_HEAP_OPTS: "-Xmx128m -Xms64m"

kafka:
  environment:
    KAFKA_HEAP_OPTS: "-Xmx256m -Xms128m"
```

메모리 계획:
| 컨테이너 | 힙 최대 | 예상 총 사용 |
|----------|---------|-------------|
| Zookeeper | 128MB | ~200MB |
| Kafka | 256MB | ~400MB |
| Redis | - | ~50MB |
| OS | - | ~200MB |
| **합계** | | **~850MB / 1GB** |

## 결과

> (해결 후 업데이트 예정)
