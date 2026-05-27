import http from 'k6/http';
import { check } from 'k6';
import { getToken } from '../lib/auth.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    peak: {
      executor: 'ramping-arrival-rate',
      startRate: 100,
      timeUnit: '1s',
      stages: [
        { duration: '30s', target: 500 },
        { duration: '30s', target: 500 },
      ],
      preAllocatedVUs: 600,
      maxVUs: 800,
    },
  },
  // No thresholds — this is an exploratory test to find limits
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const res = http.post(
    `${BASE_URL}/v1/payments`,
    JSON.stringify({
      merchantId: 'merchant-001',
      amount: 1000,
      currency: 'KRW',
      tokenId: 'token-peak',
      idempotencyKey: `s06-${__VU}-${__ITER}`,
    }),
    { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` } }
  );
  check(res, { 'peak response': (r) => r.status < 500 });
}
