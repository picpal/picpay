#!/bin/bash
set -e
source infra/infra-ids.env

DB_PASSWORD="${DB_PASSWORD:?'DB_PASSWORD 환경변수를 설정하세요'}"

echo "=== Creating RDS Subnet Group ==="
aws rds create-db-subnet-group \
  --db-subnet-group-name picpay-db-subnet-group \
  --db-subnet-group-description "PicPay DB Subnet Group" \
  --subnet-ids $PRIVATE_SUBNET_ID $PRIVATE_SUBNET_ID2 \
  --region $REGION

echo "=== Creating RDS instance (db.t3.micro, PostgreSQL 16) ==="
aws rds create-db-instance \
  --db-instance-identifier picpay-rds \
  --db-instance-class db.t3.micro \
  --engine postgres \
  --engine-version "16" \
  --master-username picpay \
  --master-user-password "$DB_PASSWORD" \
  --db-name picpay \
  --vpc-security-group-ids $SG_DB \
  --db-subnet-group-name picpay-db-subnet-group \
  --no-publicly-accessible \
  --allocated-storage 20 \
  --storage-type gp3 \
  --backup-retention-period 0 \
  --no-multi-az \
  --region $REGION

echo "=== Waiting for RDS to be available (this takes ~5-10 minutes) ==="
aws rds wait db-instance-available --db-instance-identifier picpay-rds --region $REGION

RDS_HOST=$(aws rds describe-db-instances \
  --db-instance-identifier picpay-rds \
  --region $REGION \
  --query 'DBInstances[0].Endpoint.Address' --output text)

cat >> infra/infra-ids.env <<EOF

RDS_HOST=$RDS_HOST
EOF

echo ""
echo "✅ RDS setup complete."
echo "   RDS Endpoint: $RDS_HOST"
echo "   Next: run infra/04-flyway-migrate.sh"
