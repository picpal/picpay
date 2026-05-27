import http from 'k6/http';
import { sleep } from 'k6';
import { getToken } from '../lib/auth.js';
import { checkPaymentResponse } from '../lib/checks.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    single_payment: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '3m',
      preAllocatedVUs: 200,
      maxVUs: 300,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],
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
      amount: Math.floor(Math.random() * 100000) + 1000,
      currency: 'KRW',
      tokenId: `token-${__VU}-${__ITER}`,
      idempotencyKey: `s01-${__VU}-${__ITER}-${Date.now()}`,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } }
  );
  checkPaymentResponse(res);
}
