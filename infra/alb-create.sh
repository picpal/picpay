#!/bin/bash
set -e
source infra/infra-ids.env

echo "=== Creating Application Load Balancer ==="
ALB_ARN=$(aws elbv2 create-load-balancer \
  --name picpay-alb \
  --subnets $PUBLIC_SUBNET_ID \
  --security-groups $SG_ALB \
  --type application \
  --region $REGION \
  --query 'LoadBalancers[0].LoadBalancerArn' \
  --output text)
echo "ALB ARN: $ALB_ARN"

echo "=== Creating Target Group (API Gateway :8080) ==="
TG_ARN=$(aws elbv2 create-target-group \
  --name picpay-tg \
  --protocol HTTP \
  --port 8080 \
  --vpc-id $VPC_ID \
  --health-check-protocol HTTP \
  --health-check-path /actuator/health \
  --health-check-interval-seconds 30 \
  --healthy-threshold-count 2 \
  --unhealthy-threshold-count 3 \
  --region $REGION \
  --query 'TargetGroups[0].TargetGroupArn' \
  --output text)

echo "=== Registering EC2-A as target ==="
aws elbv2 register-targets \
  --target-group-arn $TG_ARN \
  --targets Id=$EC2_A_ID \
  --region $REGION

echo "=== Creating HTTP:80 Listener ==="
aws elbv2 create-listener \
  --load-balancer-arn $ALB_ARN \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=$TG_ARN \
  --region $REGION

ALB_DNS=$(aws elbv2 describe-load-balancers \
  --load-balancer-arns $ALB_ARN \
  --region $REGION \
  --query 'LoadBalancers[0].DNSName' \
  --output text)

# Save ARNs for alb-delete.sh
cat >> infra/infra-ids.env <<EOF

ALB_ARN=$ALB_ARN
TG_ARN=$TG_ARN
ALB_DNS=$ALB_DNS
EOF

echo ""
echo "✅ ALB created."
echo "   DNS: $ALB_DNS"
echo "   (Wait ~2 minutes for health check to pass)"
echo "   Test: curl http://$ALB_DNS/actuator/health"
echo ""
echo "⚠️  부하 테스트 완료 후 반드시 infra/alb-delete.sh 실행!"
