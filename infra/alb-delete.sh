#!/bin/bash
set -e
source infra/infra-ids.env

if [ -z "$ALB_ARN" ]; then
  echo "ALB_ARN not found in infra-ids.env. Fetching..."
  ALB_ARN=$(aws elbv2 describe-load-balancers \
    --names picpay-alb \
    --region $REGION \
    --query 'LoadBalancers[0].LoadBalancerArn' \
    --output text 2>/dev/null || echo "")
fi

if [ -z "$ALB_ARN" ] || [ "$ALB_ARN" = "None" ]; then
  echo "ALB picpay-alb not found. Already deleted?"
  exit 0
fi

echo "=== Deleting ALB ==="
aws elbv2 delete-load-balancer --load-balancer-arn $ALB_ARN --region $REGION
echo "ALB deletion initiated (takes ~1 minute)..."

if [ -n "$TG_ARN" ]; then
  sleep 10
  echo "=== Deleting Target Group ==="
  aws elbv2 delete-target-group --target-group-arn $TG_ARN --region $REGION
fi

# Remove ALB entries from infra-ids.env
sed -i.bak '/^ALB_ARN=/d;/^TG_ARN=/d;/^ALB_DNS=/d' infra/infra-ids.env

echo ""
echo "✅ ALB deleted. Monthly cost impact: $0"
