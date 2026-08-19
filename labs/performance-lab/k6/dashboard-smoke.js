import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.LIFEOS_PERFORMANCE_GATEWAY_BASE_URL;
if (!baseUrl) {
  throw new Error('LIFEOS_PERFORMANCE_GATEWAY_BASE_URL is required');
}

export const options = {
  vus: Number(__ENV.LIFEOS_PERFORMANCE_VUS || 10),
  duration: __ENV.LIFEOS_PERFORMANCE_DURATION || '15s',
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  const response = http.get(`${baseUrl}/actuator/health/readiness`, {
    tags: { workload: 'bounded-readiness-smoke' },
  });
  check(response, {
    'readiness is successful': (value) => value.status === 200,
    'readiness is bounded': (value) => value.body.length <= 4096,
  });
}
