#!/bin/bash
set -e
source infra/infra-ids.env

DB_PASSWORD="${DB_PASSWORD:?'DB_PASSWORD 환경변수를 설정하세요'}"
KEY_NAME="${KEY_NAME:?'KEY_NAME 환경변수를 설정하세요'}"

echo "=== Running Flyway migration via EC2-A ==="
echo "    Target: $RDS_HOST:5432/picpay"

ssh -i ~/.ssh/${KEY_NAME}.pem -o StrictHostKeyChecking=no ubuntu@${EC2_A_PUBLIC_IP} \
  "docker run --rm \
    -e SPRING_DATASOURCE_URL=jdbc:postgresql://${RDS_HOST}:5432/picpay \
    -e SPRING_DATASOURCE_USERNAME=picpay \
    -e SPRING_DATASOURCE_PASSWORD=${DB_PASSWORD} \
    -e SPRING_FLYWAY_ENABLED=true \
    -e SPRING_MAIN_WEB_APPLICATION_TYPE=none \
    picpay-payment:latest \
    java -jar app.jar 2>&1 | tail -30"

echo ""
echo "✅ Flyway migration complete."
echo "   Next: run 'git push origin main' to trigger GitHub Actions deployment"
