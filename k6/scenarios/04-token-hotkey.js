import http from 'k6/http';
import { check } from 'k6';
import { getToken } from '../lib/auth.js';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const HOT_TOKEN_ID = 'hot-token-001';
const cacheHitRate = new Rate('cache_hit_rate');

export const options = {
  scenarios: {
    token_hotkey: {
      executor: 'constant-arrival-rate',
      rate: 500,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 600,
      maxVUs: 800,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<100'],
    http_req_failed: ['rate<0.01'],
    cache_hit_rate: ['rate>0.95'],
  },
};

export function setup() {
  return { token: getToken() };
}

export default function (data) {
  const res = http.get(
    `${BASE_URL}/v1/tokens/${HOT_TOKEN_ID}`,
    { headers: { Authorization: `Bearer ${data.token}` } }
  );
  const ok = check(res, { 'token 200': (r) => r.status === 200 });
  // Cache hits respond faster — use response time as proxy for cache hit
  // (real cache hit rate would need X-Cache header from the service)
  cacheHitRate.add(res.timings.duration < 50);
}
