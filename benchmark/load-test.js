/**
 * k6 Load Test — Distributed Event Processing Platform
 *
 * Target metrics:
 *   - 5000+ events/sec sustained throughput
 *   - p95 enqueue latency < 50ms
 *   - Error rate < 0.1%
 *
 * Run:
 *   k6 run benchmark/load-test.js
 *   k6 run --out influxdb=http://localhost:8086/k6 benchmark/load-test.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend, Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ── Custom metrics ────────────────────────────────────────────────────────────
const duplicateRate     = new Rate('duplicate_rate');
const enqueueLatency    = new Trend('enqueue_latency_ms', true);
const successCounter    = new Counter('events_accepted');
const errorCounter      = new Counter('events_errored');

// ── Test configuration ────────────────────────────────────────────────────────
export const options = {
  scenarios: {
    // Ramp-up scenario: gradually increase to 50 VUs
    ramp_up: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 10  },  // warm-up
        { duration: '60s', target: 50  },  // ramp to peak load
        { duration: '120s', target: 50 },  // sustain peak load
        { duration: '30s', target: 0   },  // ramp down
      ],
      gracefulRampDown: '10s',
    },
    // Constant arrival rate for throughput measurement
    constant_arrival: {
      executor: 'constant-arrival-rate',
      rate: 5000,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 100,
      maxVUs: 200,
      startTime: '30s',  // start after initial ramp-up
    },
  },
  thresholds: {
    // p95 enqueue latency must be under 50ms
    'enqueue_latency_ms': ['p(95)<50', 'p(99)<100'],
    // HTTP errors must stay below 0.1%
    'http_req_failed': ['rate<0.001'],
    // p95 of ALL HTTP requests
    'http_req_duration': ['p(95)<100'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
};

// ── Constants ─────────────────────────────────────────────────────────────────
const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const API_KEY   = __ENV.API_KEY   || 'prod-key-alpha-001';
const WEBHOOK_URL = __ENV.WEBHOOK_URL || 'https://webhook.site/placeholder';

const EVENT_TYPES = [
  'ORDER_UPDATE',
  'DRIVER_ASSIGNED',
  'PAYMENT_DONE',
  'TRIP_STARTED',
  'TRIP_ENDED',
];

const PARTITION_KEYS = Array.from({ length: 100 }, (_, i) => `ORD-${String(i).padStart(5, '0')}`);

// ── Helpers ───────────────────────────────────────────────────────────────────
function randomElement(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

function buildPayload(eventType, partitionKey) {
  const base = { id: partitionKey, status: 'PROCESSING', timestamp: new Date().toISOString() };
  switch (eventType) {
    case 'ORDER_UPDATE':
      return { ...base, orderId: partitionKey, newStatus: 'PICKED_UP' };
    case 'DRIVER_ASSIGNED':
      return { ...base, orderId: partitionKey, driverId: `DRV-${Math.floor(Math.random() * 10000)}` };
    case 'PAYMENT_DONE':
      return { ...base, paymentId: partitionKey, amount: (Math.random() * 1000).toFixed(2) };
    case 'TRIP_STARTED':
      return { ...base, tripId: partitionKey, origin: 'Delhi', destination: 'Gurugram' };
    case 'TRIP_ENDED':
      return { ...base, tripId: partitionKey, fare: (Math.random() * 500).toFixed(2) };
    default:
      return base;
  }
}

// ── Default function (runs per VU iteration) ──────────────────────────────────
export default function () {
  const eventId     = uuidv4();
  const eventType   = randomElement(EVENT_TYPES);
  const partitionKey = randomElement(PARTITION_KEYS);

  const requestBody = JSON.stringify({
    eventId,
    eventType,
    partitionKey,
    payload: buildPayload(eventType, partitionKey),
    webhookUrl: WEBHOOK_URL,
    timestamp: new Date().toISOString(),
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-KEY': API_KEY,
    },
    timeout: '5s',
  };

  const startTime = Date.now();
  const response = http.post(`${BASE_URL}/api/v1/events`, requestBody, params);
  const latency  = Date.now() - startTime;

  enqueueLatency.add(latency);

  const isSuccess = check(response, {
    'status is 202 or 200': (r) => r.status === 202 || r.status === 200,
    'response has eventId': (r) => {
      try { return JSON.parse(r.body).eventId !== undefined; } catch { return false; }
    },
    'response has status': (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.status === 'QUEUED' || body.status === 'DUPLICATE';
      } catch { return false; }
    },
  });

  if (isSuccess) {
    successCounter.add(1);
    try {
      const body = JSON.parse(response.body);
      duplicateRate.add(body.status === 'DUPLICATE' ? 1 : 0);
    } catch (_) {}
  } else {
    errorCounter.add(1);
    if (__ENV.VERBOSE) {
      console.error(`Request failed: ${response.status} — ${response.body}`);
    }
  }
}

// ── Stats endpoint smoke test ─────────────────────────────────────────────────
export function statsCheck() {
  const response = http.get(`${BASE_URL}/api/v1/stats`, {
    headers: { 'X-API-KEY': API_KEY },
  });
  check(response, {
    'stats returns 200': (r) => r.status === 200,
  });
}

// ── Summary ───────────────────────────────────────────────────────────────────
export function handleSummary(data) {
  const passed = data.metrics.http_req_failed.values.rate < 0.001;
  const p95Ok  = data.metrics.enqueue_latency_ms
    ? data.metrics.enqueue_latency_ms.values['p(95)'] < 50
    : true;

  console.log('\n══════════════════════════════════════════════');
  console.log('    DISTRIBUTED EVENT PLATFORM — LOAD TEST    ');
  console.log('══════════════════════════════════════════════');
  console.log(`Total requests:      ${data.metrics.http_reqs.values.count}`);
  console.log(`Requests/sec:        ${data.metrics.http_reqs.values.rate.toFixed(1)}`);
  console.log(`Events accepted:     ${data.metrics.events_accepted ? data.metrics.events_accepted.values.count : 'N/A'}`);
  console.log(`Enqueue p50:         ${data.metrics.enqueue_latency_ms ? data.metrics.enqueue_latency_ms.values.med.toFixed(2) + 'ms' : 'N/A'}`);
  console.log(`Enqueue p95:         ${data.metrics.enqueue_latency_ms ? data.metrics.enqueue_latency_ms.values['p(95)'].toFixed(2) + 'ms' : 'N/A'}`);
  console.log(`Enqueue p99:         ${data.metrics.enqueue_latency_ms ? data.metrics.enqueue_latency_ms.values['p(99)'].toFixed(2) + 'ms' : 'N/A'}`);
  console.log(`Error rate:          ${(data.metrics.http_req_failed.values.rate * 100).toFixed(3)}%`);
  console.log(`Duplicate rate:      ${data.metrics.duplicate_rate ? (data.metrics.duplicate_rate.values.rate * 100).toFixed(3) + '%' : '0.000%'}`);
  console.log('──────────────────────────────────────────────');
  console.log(`SLA — p95 < 50ms:    ${p95Ok ? '✅ PASS' : '❌ FAIL'}`);
  console.log(`SLA — error < 0.1%:  ${passed ? '✅ PASS' : '❌ FAIL'}`);
  console.log('══════════════════════════════════════════════\n');

  return {
    stdout: JSON.stringify(data, null, 2),
  };
}
