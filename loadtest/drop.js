// k6 rehearsal of a drop: far more shoppers than pairs, all at once.
//
// Each iteration is one shopper: join the line, wait to be admitted, check out (retrying with the same
// Idempotency-Key on errors, like the storefront does), then pay, fail and retry, or walk away.
// Walked-away holds expire and go back on sale. scripts/loadtest.sh then audits the drop:
// sold <= stock, nobody over the limit, nobody charged twice.
//
//   docker run --rm --network host -v "$PWD/loadtest:/scripts" grafana/k6:0.54.0 run /scripts/drop.js
//
// Env: API (default http://localhost:8080), SHOPPERS (1500), VUS (300), STOCK per size (150)

import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const API = __ENV.API || 'http://localhost:8080';
const SHOPPERS = Number(__ENV.SHOPPERS || 1500);
const VUS = Number(__ENV.VUS || 300);
const STOCK = Number(__ENV.STOCK || 150);
const SLUG = __ENV.SLUG || `load-${Date.now()}`;

const paid = new Counter('orders_paid');
const unitsPaid = new Counter('units_paid');
const soldOut = new Counter('sold_out_answers');
const limited = new Counter('limit_answers');
const abandoned = new Counter('abandoned_holds');
const retried = new Counter('checkout_retries');
const queueWait = new Trend('queue_wait_ms', true);
const checkoutTime = new Trend('checkout_ms', true);

// 409 (sold out, limit) and 401/403 are answers, not failures
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 409, 422));

export const options = {
  scenarios: {
    drop: { executor: 'shared-iterations', vus: VUS, iterations: SHOPPERS, maxDuration: '6m' },
  },
  thresholds: {
    http_req_failed: ['rate<0.02'],
    checks: ['rate>0.99'],
    checkout_ms: ['p(95)<1500'],
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  // opens 15 s from now, so every VU is already in line when it does
  const drop = http.post(`${API}/dev/drops/demo?slug=${SLUG}&stock=${STOCK}&limit=2&admitPerSecond=400&ttlSeconds=30&opensInSeconds=15&minutes=20`);
  check(drop, { 'drop created': (r) => r.status === 200 });
  const tokens = http.post(`${API}/dev/customers/bulk?count=${SHOPPERS}&prefix=${SLUG}`, null, { timeout: '120s' });
  check(tokens, { 'shoppers created': (r) => r.status === 200 });
  return { dropId: drop.json('id'), tokens: tokens.json() };
}

export default function (data) {
  const i = exec.scenario.iterationInTest;
  const auth = { Authorization: `Bearer ${data.tokens[i]}` };
  const json = { ...auth, 'Content-Type': 'application/json' };

  // join and wait our turn
  const joined = Date.now();
  let status = http.post(`${API}/api/drops/${SLUG}/queue`, null, { headers: auth });
  if (status.status === 409) { soldOut.add(1); return; }
  let entry = null;
  for (let polls = 0; polls < 240; polls++) {
    const s = status.json();
    if (s && s.state === 'ADMITTED') { entry = s.entryToken; break; }
    if (s && (s.state === 'SOLD_OUT' || s.state === 'CLOSED')) { soldOut.add(1); return; }
    sleep(Math.min(2, ((s && s.pollAfterMs) || 1000) / 1000) * (0.75 + Math.random() * 0.5));
    status = http.get(`${API}/api/drops/${SLUG}/queue/me`, { headers: auth });
  }
  if (!entry) return;
  queueWait.add(Date.now() - joined);

  // check out: a random size, mostly one pair, sometimes the max
  const sku = Math.random() < 0.5 ? 'MR-UK8' : 'MR-UK9';
  const quantity = Math.random() < 0.8 ? 1 : 2;
  const phone = `9${String(100000000 + i).slice(-9)}`;
  const body = JSON.stringify({
    sku, quantity, phone,
    address: { name: `Shopper ${i}`, line1: '1 Load Test Road', city: 'Pune', state: 'MH', pincode: '411001' },
  });
  const key = `load-${SLUG}-${i}`;
  let order = null;
  const started = Date.now();
  for (let attempt = 0; attempt < 6; attempt++) {
    const r = http.post(`${API}/api/drops/${SLUG}/checkout`, body, {
      headers: { ...json, 'Idempotency-Key': key, 'X-Entry-Token': entry },
    });
    if (r.status === 201) { order = r.json(); break; }
    const code = r.status === 0 ? '' : (r.json('code') || '');
    if (r.status >= 500 || r.status === 0 || code === 'request_in_progress') {
      retried.add(1);
      sleep(0.2 * 2 ** attempt);
      continue;
    }
    if (code === 'sold_out' || code === 'insufficient_stock') soldOut.add(1);
    if (code === 'limit_reached' || code === 'phone_limit_reached') limited.add(1);
    break;
  }
  checkoutTime.add(Date.now() - started);
  if (!order) return;
  check(order, { 'order is pending payment': (o) => o.status === 'PENDING_PAYMENT' });

  // pay: 85% pay, 5% get declined then pay, 10% walk away (their hold expires and the pair is resold)
  const roll = Math.random();
  if (roll < 0.10) { abandoned.add(1); return; }
  const gatewayOrderId = order.payment.gatewayOrderId;
  sleep(Math.random() * 2);
  if (roll < 0.15) {
    http.post(`${API}/dev/simulator/pay`, JSON.stringify({ gatewayOrderId, status: 'failed' }), { headers: { 'Content-Type': 'application/json' } });
  }
  const p = http.post(`${API}/dev/simulator/pay`, JSON.stringify({ gatewayOrderId, status: 'captured' }),
    { headers: { 'Content-Type': 'application/json' } });
  check(p, { 'payment accepted': (r) => r.status === 200 });
  const after = http.get(`${API}/api/orders/${order.id}`, { headers: auth });
  if (after.json('status') === 'PAID') {
    paid.add(1);
    unitsPaid.add(order.quantity);
  }
}

export function handleSummary(data) {
  const m = (name, stat = 'count') => (data.metrics[name] ? data.metrics[name].values[stat] : 0);
  const line = `drop ${SLUG}: ${m('orders_paid')} orders paid (${m('units_paid')} pairs of ${2 * STOCK}), `
    + `${m('sold_out_answers')} told sold out, ${m('limit_answers')} hit the limit, ${m('abandoned_holds')} walked away; `
    + `checkout p95 ${Math.round(m('checkout_ms', 'p(95)'))} ms, queue wait p95 ${Math.round(m('queue_wait_ms', 'p(95)'))} ms, `
    + `http failures ${(100 * m('http_req_failed', 'rate')).toFixed(2)}%`;
  return {
    stdout: `\n${line}\n`,
    '/scripts/result.json': JSON.stringify({
      slug: SLUG, dropId: data.setup_data ? data.setup_data.dropId : null, line, metrics: data.metrics,
    }, null, 2),
  };
}
