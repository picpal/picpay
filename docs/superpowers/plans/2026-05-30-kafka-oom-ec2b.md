# Kafka OOM on EC2-B (t3.micro)

> 날짜: 2026-05-30

## 오류

```
Container ubuntu-token-1  Error
dependency failed to start: container ubuntu-token-1 is unhealthy
```

EC2-B에서 Kafka 컨테이너가 계속 재시작(`Restarting (1)`).

## 실제 에러 메시지

### GitHub Actions 로그 (docker compose up)
```
Container ubuntu-token-1  Error
dependency failed to start: container ubuntu-token-1 is unhealthy
Process completed with exit code 1.
```

### EC2-B Kafka 컨테이너 로그 (`docker logs ubuntu-kafka-1`)
```
# Out of Memory Error (os_linux.cpp:3121), pid=1, tid=58
#
# JRE version:  (11.0.20+8) (build )
# Java VM: OpenJDK 64-Bit Server VM (11.0.20+8-LTS, mixed mode, ...)
# Core dump will be written. Default location: ...
#
#   The process is running with CompressedOops enabled,
#   and the Java Heap may be blocking the growth of the native heap
#   Decrease Java heap size (-Xmx/-Xms)
#  Out of Memory Error (os_linux.cpp:3121), pid=1, tid=58
```

JVM 시작 시점 메모리 상태:
```
os.memory.free=11MB          ← JVM 가용 메모리 11MB 뿐
os.memory.max=228MB
Memory: physical 963376k(72672k free)  ← 호스트 여유 RAM 72MB
```

## 원인 분석

EC2-B t3.micro 1GB RAM에서 JVM 힙 기본값이 너무 큼:
- Zookeeper: 기본 힙 ~512MB
- Kafka: 기본 힙 ~1GB
- Redis: ~50MB

합계 ~1.56GB > 1GB RAM → Kafka JVM OOM → 재시작 반복

token 서비스는 Kafka에 의존하므로 헬스체크 실패 → `ubuntu-token-1 is unhealthy`

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
