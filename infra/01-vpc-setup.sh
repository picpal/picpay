#!/bin/bash
set -e
REGION="ap-northeast-2"
VPC_CIDR="10.0.0.0/16"
PUBLIC_CIDR="10.0.1.0/24"
PRIVATE_CIDR="10.0.2.0/24"
AZ="ap-northeast-2a"

echo "=== [1/8] Creating VPC ==="
VPC_ID=$(aws ec2 create-vpc --cidr-block $VPC_CIDR --region $REGION \
  --tag-specifications "ResourceType=vpc,Tags=[{Key=Name,Value=picpay-vpc}]" \
  --query 'Vpc.VpcId' --output text)
aws ec2 modify-vpc-attribute --vpc-id $VPC_ID --enable-dns-hostnames
echo "VPC_ID=$VPC_ID"

echo "=== [2/8] Creating Public Subnet ==="
PUBLIC_SUBNET_ID=$(aws ec2 create-subnet --vpc-id $VPC_ID \
  --cidr-block $PUBLIC_CIDR --availability-zone $AZ \
  --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=picpay-public}]" \
  --query 'Subnet.SubnetId' --output text)
aws ec2 modify-subnet-attribute --subnet-id $PUBLIC_SUBNET_ID --map-public-ip-on-launch

echo "=== [3/8] Creating Private + Additional Public Subnets ==="
PRIVATE_SUBNET_ID=$(aws ec2 create-subnet --vpc-id $VPC_ID \
  --cidr-block $PRIVATE_CIDR --availability-zone $AZ \
  --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=picpay-private}]" \
  --query 'Subnet.SubnetId' --output text)

# Second AZ (required for RDS Subnet Group AND ALB — ALB requires 2+ AZs)
AZ2="ap-northeast-2c"
PUBLIC_SUBNET_ID2=$(aws ec2 create-subnet --vpc-id $VPC_ID \
  --cidr-block "10.0.4.0/24" --availability-zone $AZ2 \
  --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=picpay-public-2}]" \
  --query 'Subnet.SubnetId' --output text)
aws ec2 modify-subnet-attribute --subnet-id $PUBLIC_SUBNET_ID2 --map-public-ip-on-launch
PRIVATE_SUBNET_ID2=$(aws ec2 create-subnet --vpc-id $VPC_ID \
  --cidr-block "10.0.3.0/24" --availability-zone $AZ2 \
  --tag-specifications "ResourceType=subnet,Tags=[{Key=Name,Value=picpay-private-2}]" \
  --query 'Subnet.SubnetId' --output text)

echo "=== [4/8] Creating Internet Gateway ==="
IGW_ID=$(aws ec2 create-internet-gateway --region $REGION \
  --tag-specifications "ResourceType=internet-gateway,Tags=[{Key=Name,Value=picpay-igw}]" \
  --query 'InternetGateway.InternetGatewayId' --output text)
aws ec2 attach-internet-gateway --internet-gateway-id $IGW_ID --vpc-id $VPC_ID

echo "=== [5/8] Creating Route Tables ==="
PUBLIC_RT=$(aws ec2 create-route-table --vpc-id $VPC_ID \
  --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=picpay-public-rt}]" \
  --query 'RouteTable.RouteTableId' --output text)
aws ec2 create-route --route-table-id $PUBLIC_RT --destination-cidr-block 0.0.0.0/0 --gateway-id $IGW_ID
aws ec2 associate-route-table --route-table-id $PUBLIC_RT --subnet-id $PUBLIC_SUBNET_ID
aws ec2 associate-route-table --route-table-id $PUBLIC_RT --subnet-id $PUBLIC_SUBNET_ID2

PRIVATE_RT=$(aws ec2 create-route-table --vpc-id $VPC_ID \
  --tag-specifications "ResourceType=route-table,Tags=[{Key=Name,Value=picpay-private-rt}]" \
  --query 'RouteTable.RouteTableId' --output text)
aws ec2 associate-route-table --route-table-id $PRIVATE_RT --subnet-id $PRIVATE_SUBNET_ID
aws ec2 associate-route-table --route-table-id $PRIVATE_RT --subnet-id $PRIVATE_SUBNET_ID2

echo "=== [6/8] Creating Security Groups ==="
SG_ALB=$(aws ec2 create-security-group --group-name picpay-alb \
  --description "ALB SG - load balancer for load testing" \
  --vpc-id $VPC_ID --query 'GroupId' --output text)
aws ec2 create-tags --resources $SG_ALB --tags Key=Name,Value=picpay-alb

SG_WAS=$(aws ec2 create-security-group --group-name picpay-was \
  --description "WAS SG - Spring Boot services" \
  --vpc-id $VPC_ID --query 'GroupId' --output text)
aws ec2 create-tags --resources $SG_WAS --tags Key=Name,Value=picpay-was

SG_MIDDLEWARE=$(aws ec2 create-security-group --group-name picpay-middleware \
  --description "Middleware SG - Kafka + Redis" \
  --vpc-id $VPC_ID --query 'GroupId' --output text)
aws ec2 create-tags --resources $SG_MIDDLEWARE --tags Key=Name,Value=picpay-middleware

SG_DB=$(aws ec2 create-security-group --group-name picpay-db \
  --description "DB SG - RDS PostgreSQL" \
  --vpc-id $VPC_ID --query 'GroupId' --output text)
aws ec2 create-tags --resources $SG_DB --tags Key=Name,Value=picpay-db

echo "=== [7/8] Configuring Security Group Rules ==="
MY_IP=$(curl -s https://checkip.amazonaws.com | tr -d '\n')/32
echo "Your IP: $MY_IP"

# sg-alb: HTTP 80 from anywhere
aws ec2 authorize-security-group-ingress --group-id $SG_ALB --protocol tcp --port 80 --cidr 0.0.0.0/0

# sg-was: 8080 from developer IP (direct access), 8080-8084 from ALB, SSH from developer IP
aws ec2 authorize-security-group-ingress --group-id $SG_WAS --protocol tcp --port 8080 --cidr $MY_IP
aws ec2 authorize-security-group-ingress --group-id $SG_WAS --protocol tcp --port 22 --cidr $MY_IP
aws ec2 authorize-security-group-ingress --group-id $SG_WAS --protocol tcp --from-port 8080 --to-port 8084 --source-group $SG_ALB

# sg-middleware: Kafka, Zookeeper, Redis from WAS; SSH from WAS (bastion pattern)
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 9092 --source-group $SG_WAS
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 2181 --source-group $SG_WAS
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 6379 --source-group $SG_WAS
aws ec2 authorize-security-group-ingress --group-id $SG_MIDDLEWARE --protocol tcp --port 22 --source-group $SG_WAS

# sg-db: PostgreSQL 5432 from WAS only
aws ec2 authorize-security-group-ingress --group-id $SG_DB --protocol tcp --port 5432 --source-group $SG_WAS

echo "=== [8/8] Saving resource IDs ==="
mkdir -p infra
cat > infra/infra-ids.env <<EOF
# Auto-generated by 01-vpc-setup.sh — DO NOT EDIT MANUALLY
REGION=$REGION
VPC_ID=$VPC_ID
PUBLIC_SUBNET_ID=$PUBLIC_SUBNET_ID
PUBLIC_SUBNET_ID2=$PUBLIC_SUBNET_ID2
PRIVATE_SUBNET_ID=$PRIVATE_SUBNET_ID
PRIVATE_SUBNET_ID2=$PRIVATE_SUBNET_ID2
IGW_ID=$IGW_ID
SG_ALB=$SG_ALB
SG_WAS=$SG_WAS
SG_MIDDLEWARE=$SG_MIDDLEWARE
SG_DB=$SG_DB
MY_IP_AT_SETUP=$MY_IP
EOF

echo ""
echo "✅ VPC setup complete. IDs saved to infra/infra-ids.env"
echo "   Next: run infra/02-ec2-setup.sh"
