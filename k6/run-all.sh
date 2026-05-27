#!/bin/bash
set -e

BASE_URL="${BASE_URL:?'BASE_URL required (e.g. http://ALB-DNS or http://EC2-A-IP:8080)'}"
API_KEY="${API_KEY:?'API_KEY required'}"

mkdir -p docs/load-test

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
SCENARIOS=(
  "01-single-payment"
  "02-concurrent-payment"
  "03-billing-concurrent"
  "04-token-hotkey"
  "05-composite"
  "06-peak"
)

for scenario in "${SCENARIOS[@]}"; do
  echo ""
  echo "=========================================="
  echo "Running: $scenario"
  echo "=========================================="
  k6 run \
    --env BASE_URL="$BASE_URL" \
    --env API_KEY="$API_KEY" \
    --out json="docs/load-test/${scenario}-${TIMESTAMP}.json" \
    "k6/scenarios/${scenario}.js"
  echo "Completed: $scenario"
  sleep 15
done

echo ""
echo "All scenarios complete. Results: docs/load-test/"
