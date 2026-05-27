import http from 'k6/http';
import { check } from 'k6';
import { getToken } from '../lib/auth.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const SHARED_KEY = `idempotency-test-${Date.now()}`;

export const options = {
  scenarios: {
    concurrent_payment: {
      executor: 'constant-arrival-rate',
      rate: 50,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 100,
      maxVUs: 150,
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/v1/payments`,
    JSON.stringify({
      merchantId: 'merchant-001',
      amount: 10000,
      currency: 'KRW',
      tokenId: 'token-idempotency-test',
      idempotencyKey: SHARED_KEY,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } }
  );
  check(res, {
    'idempotent 200 or 201': (r) => r.status === 200 || r.status === 201,
  });
}
