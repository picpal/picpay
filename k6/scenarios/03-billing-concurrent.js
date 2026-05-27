import http from 'k6/http';
import { check } from 'k6';
import { getToken } from '../lib/auth.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    billing_concurrent: {
      executor: 'constant-arrival-rate',
      rate: 30,
      timeUnit: '1s',
      duration: '1m',
      preAllocatedVUs: 60,
      maxVUs: 90,
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
  const res = http.get(
    `${BASE_URL}/v1/billing/plans/plan-001/history`,
    { headers: { Authorization: `Bearer ${data.token}` } }
  );
  check(res, { 'billing history 200': (r) => r.status === 200 });
}
