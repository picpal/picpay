import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const API_KEY = __ENV.API_KEY || 'test-api-key';

export function getToken() {
  const res = http.post(`${BASE_URL}/v1/auth/token`, null, {
    headers: { 'X-Api-Key': API_KEY },
  });
  check(res, { 'auth 200': (r) => r.status === 200 });
  return res.json('data.token');
}
