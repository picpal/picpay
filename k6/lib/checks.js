import { check } from 'k6';

export function checkPaymentResponse(res) {
  return check(res, {
    'status 200 or 201': (r) => r.status === 200 || r.status === 201,
    'has tid': (r) => {
      try { return r.json('data.tid') !== undefined; } catch { return false; }
    },
  });
}

export function checkOk(res) {
  return check(res, { 'status 200': (r) => r.status === 200 });
}
