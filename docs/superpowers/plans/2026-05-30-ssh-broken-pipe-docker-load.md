# SSH Broken Pipe during docker load on EC2-A

> 날짜: 2026-05-30

## 오류

```
Loading gateway image...
client_loop: send disconnect: Broken pipe
Process completed with exit code 255.
```

"Deploy WAS services on EC2-A" 단계에서 gateway 이미지 로딩 약 4분 후 SSH 연결 단절.

## 원인 분석

`.dockerignore`에서 `**/build` 제거 후 JAR 파일이 Docker 이미지에 포함되면서
이미지 크기가 대폭 증가:

| 이미지 | 크기 |
|--------|------|
| picpay-gateway | 517MB |
| picpay-token | 550MB |
| picpay-billing | 599MB |
| picpay-payment | 591MB |
| picpay-notification | 568MB |

EC2-A t3.micro 메모리 상태:
```
Mem: total=940MB, used=600MB, free=70MB, available=188MB
Swap: 0 (스왑 없음)
```

`docker load`로 500MB+ 이미지를 decompression + 레이어 쓰기 시 4분 이상 소요.
이 동안 SSH 서버가 클라이언트에 응답 없음 → `Broken pipe`.

## 시도한 방법

### Fix 1: SSH ServerAliveInterval 추가

`cd.yml` "Deploy WAS services on EC2-A" SSH 커맨드에 keepalive 옵션 추가:
```
-o ServerAliveInterval=30 -o ServerAliveCountMax=20
```
→ 30초마다 keepalive 패킷 전송, 최대 10분 동안 연결 유지.

### Fix 2: docker system prune으로 이전 이미지 정리

이미지 로딩 전 `docker system prune -f` 실행:
- 미사용 이미지/컨테이너/네트워크 제거
- 메모리·디스크 여유 확보

## 결과

> (해결 후 업데이트 예정)
