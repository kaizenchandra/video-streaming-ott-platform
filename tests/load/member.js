import http from 'k6/http';
import { check } from 'k6';
export const options = {
  scenarios: { account_read: { executor: 'constant-vus', vus: 5, duration: '15s' } },
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'], http_req_duration: ['p(95)<500'] },
};
export default function () {
  const r = http.get(`${__ENV.BASE_URL}/v1/accounts/me`, {
    headers: { Authorization: `Bearer ${__ENV.ACCESS_TOKEN}` },
    tags: { owner: 'member', operation: 'account_read' },
  });
  check(r, { 'authenticated owned account': r.status === 200 && r.json('id') === __ENV.ACCOUNT_ID });
}
