import http from 'k6/http';
import {check, sleep} from 'k6';

const targetUrl = __ENV.TARGET_URL;
if (typeof targetUrl !== 'string' || targetUrl.length === 0) {
  throw new Error('TARGET_URL is required');
}

const vus = Number(__ENV.VUS || '10');
if (!Number.isFinite(vus) || !Number.isInteger(vus) || vus <= 0) {
  throw new Error('VUS must be a positive integer');
}

export const options = {
  vus,
  duration: __ENV.DURATION || '15s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
    checks: ['rate==1'],
  },
};

export default function () {
  const response = http.get(`${targetUrl.replace(/\/+$/, '')}/actuator/health/readiness`, {
    tags: {operation: 'readiness-smoke'},
    timeout: '5s',
  });
  check(response, {
    'readiness status is 200': (result) => result.status === 200,
    'readiness payload is UP': (result) => result.json('status') === 'UP',
  });
  sleep(0.1);
}
