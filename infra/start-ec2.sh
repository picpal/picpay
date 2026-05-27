#!/bin/bash
set -e
source infra/infra-ids.env

echo "=== Starting EC2-A (WAS) and EC2-B (Middleware) ==="
aws ec2 start-instances --instance-ids $EC2_A_ID $EC2_B_ID --region $REGION

echo "=== Waiting for instances to be running ==="
aws ec2 wait instance-running --instance-ids $EC2_A_ID $EC2_B_ID --region $REGION

EC2_A_PUBLIC_IP=$(aws ec2 describe-instances \
  --instance-ids $EC2_A_ID \
  --region $REGION \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)

echo "EC2-A Public IP: $EC2_A_PUBLIC_IP"

# Update infra-ids.env with new IP
sed -i.bak "s/EC2_A_PUBLIC_IP=.*/EC2_A_PUBLIC_IP=$EC2_A_PUBLIC_IP/" infra/infra-ids.env

# Update ~/.ssh/config (EC2-A entry)
if grep -q "Host picpay-was" ~/.ssh/config 2>/dev/null; then
  # macOS sed requires backup extension
  sed -i.bak "/Host picpay-was/{n;s/HostName .*/HostName $EC2_A_PUBLIC_IP/;}" ~/.ssh/config
  echo "✅ ~/.ssh/config updated"
else
  echo "⚠️  ~/.ssh/config에 picpay-was 항목이 없습니다. 수동으로 추가해주세요:"
  echo "   Host picpay-was"
  echo "       HostName $EC2_A_PUBLIC_IP"
  echo "       User ubuntu"
  echo "       IdentityFile ~/.ssh/${KEY_NAME:-picpay-key}.pem"
  echo "       StrictHostKeyChecking no"
fi

# Update GitHub Actions secret (requires gh CLI)
if command -v gh &>/dev/null; then
  gh secret set EC2_HOST --body "$EC2_A_PUBLIC_IP"
  echo "✅ GitHub secret EC2_HOST updated"
else
  echo "⚠️  gh CLI not found. Update EC2_HOST secret manually in GitHub Actions."
fi

echo ""
echo "✅ EC2 started. Connect: ssh picpay-was"
