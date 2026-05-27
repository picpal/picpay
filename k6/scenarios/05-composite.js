import http from 'k6/http';
import { check } from 'k6';
import { getToken } from '../lib/auth.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    composite: {
      executor: 'constant-arrival-rate',
      rate: 200,
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 300,
      maxVUs: 400,
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
  const roll = Math.random();

  if (roll < 0.5) {
    const res = http.post(
      `${BASE_URL}/v1/payments`,
      JSON.stringify({
        merchantId: 'merchant-001',
        amount: 5000,
        currency: 'KRW',
        tokenId: 'token-composite',
        idempotencyKey: `s05-${__VU}-${__ITER}`,
      }),
      { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } }
    );
    check(res, { 'payment ok': (r) => r.status === 200 || r.status === 201 });
  } else if (roll < 0.8) {
    const res = http.get(
      `${BASE_URL}/v1/tokens/hot-token-001`,
      { headers: { Authorization: `Bearer ${data.token}` } }
    );
    check(res, { 'token ok': (r) => r.status === 200 });
  } else {
    const res = http.get(
      `${BASE_URL}/v1/billing/plans/plan-001/history`,
      { headers: { Authorization: `Bearer ${data.token}` } }
    );
    check(res, { 'billing ok': (r) => r.status === 200 });
  }
}
