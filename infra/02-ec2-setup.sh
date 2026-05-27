#!/bin/bash
set -e
source infra/infra-ids.env

# Ubuntu 24.04 LTS ap-northeast-2 (update if needed: aws ec2 describe-images --owners 099720109477 --filters "Name=name,Values=ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-*" --query 'sort_by(Images,&CreationDate)[-1].ImageId')
AMI_ID="${AMI_ID:-ami-0c9c942bd7bf113a2}"
INSTANCE_TYPE="t3.micro"
KEY_NAME="${KEY_NAME:?'KEY_NAME 환경변수를 설정하세요 (예: export KEY_NAME=picpay-key)'}"

DOCKER_INSTALL=$(cat <<'USERDATA'
#!/bin/bash
apt-get update -y
apt-get install -y docker.io docker-compose-v2 netcat-openbsd
usermod -aG docker ubuntu
systemctl enable docker
systemctl start docker
USERDATA
)

echo "=== Creating EC2-A (WAS, public subnet) ==="
EC2_A_ID=$(aws ec2 run-instances \
  --image-id $AMI_ID \
  --instance-type $INSTANCE_TYPE \
  --key-name $KEY_NAME \
  --security-group-ids $SG_WAS \
  --subnet-id $PUBLIC_SUBNET_ID \
  --associate-public-ip-address \
  --user-data "$DOCKER_INSTALL" \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":20,"VolumeType":"gp3"}}]' \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=picpay-ec2-a-was}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "EC2-A ID: $EC2_A_ID"

echo "=== Creating EC2-B (Middleware, private subnet) ==="
EC2_B_ID=$(aws ec2 run-instances \
  --image-id $AMI_ID \
  --instance-type $INSTANCE_TYPE \
  --key-name $KEY_NAME \
  --security-group-ids $SG_MIDDLEWARE \
  --subnet-id $PRIVATE_SUBNET_ID \
  --no-associate-public-ip-address \
  --user-data "$DOCKER_INSTALL" \
  --block-device-mappings '[{"DeviceName":"/dev/sda1","Ebs":{"VolumeSize":20,"VolumeType":"gp3"}}]' \
  --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=picpay-ec2-b-middleware}]" \
  --query 'Instances[0].InstanceId' --output text)
echo "EC2-B ID: $EC2_B_ID"

echo "=== Waiting for instances to be running ==="
aws ec2 wait instance-running --instance-ids $EC2_A_ID $EC2_B_ID --region $REGION
echo "Both instances running."

EC2_A_PUBLIC_IP=$(aws ec2 describe-instances --instance-ids $EC2_A_ID \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)
EC2_B_PRIVATE_IP=$(aws ec2 describe-instances --instance-ids $EC2_B_ID \
  --query 'Reservations[0].Instances[0].PrivateIpAddress' --output text)

# Append to infra-ids.env
cat >> infra/infra-ids.env <<EOF

EC2_A_ID=$EC2_A_ID
EC2_A_PUBLIC_IP=$EC2_A_PUBLIC_IP
EC2_B_ID=$EC2_B_ID
EC2_B_PRIVATE_IP=$EC2_B_PRIVATE_IP
EOF

echo ""
echo "✅ EC2 setup complete."
echo "   EC2-A Public IP: $EC2_A_PUBLIC_IP"
echo "   EC2-B Private IP: $EC2_B_PRIVATE_IP"
echo ""
echo "   Wait ~2 minutes for Docker to install, then update ~/.ssh/config:"
echo "   Host picpay-was"
echo "       HostName $EC2_A_PUBLIC_IP"
echo "       User ubuntu"
echo "       IdentityFile ~/.ssh/${KEY_NAME}.pem"
echo "       StrictHostKeyChecking no"
echo ""
echo "   Next: run infra/03-rds-setup.sh"
