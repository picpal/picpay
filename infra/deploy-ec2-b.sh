#!/bin/bash
set -e
for img in zookeeper kafka redis; do
  echo "Loading $img..."
  docker load < /tmp/${img}.tar.gz
done
cp /tmp/docker-compose.ec2-b.yml /home/ubuntu/docker-compose.yml
EC2_B_PRIVATE_IP=$(hostname -I | awk '{print $1}')
EC2_B_PRIVATE_IP=$EC2_B_PRIVATE_IP docker compose -f /home/ubuntu/docker-compose.yml up -d --remove-orphans
echo "Middleware started on EC2-B"
