// DEV CMP -> shared SessionClipQueue -> AI client -> real STT/LLM/TTS.
// This does not exercise Telegram webhook, media download, or message delivery.
// Example: k6 run -e BASE_URL=https://DEV_CMP -e AUDIO_FILE=/tmp/probe.ogg \
//   -e RATE_RPM=600 -e DURATION=2m -e PREALLOCATED_VUS=500 load/cmp-real-clips.js
// Set LOAD_TEST_TOKEN in the environment; do not put it on the command line.

import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const accepted = new Counter('clip_accepted');
const failed = new Counter('clip_failed');
const elapsed = new Trend('clip_e2e', true);
const audio = open(__ENV.AUDIO_FILE, 'b');
const rate = Number(__ENV.RATE_RPM || 600);
const duration = __ENV.DURATION || '2m';
const vus = Number(__ENV.PREALLOCATED_VUS || 500);
const runId = __ENV.RUN_ID || String(Date.now());
let sessionId;
let errorsLogged = 0;

if (!Number.isInteger(rate) || rate < 1 || rate > 600) throw new Error('RATE_RPM must be 1..600');
if (!Number.isInteger(vus) || vus < 1) throw new Error('PREALLOCATED_VUS must be positive');
if (!/^[A-Za-z0-9_-]{1,40}$/.test(runId)) throw new Error('RUN_ID has invalid characters');
if (!__ENV.AUDIO_FILE.toLowerCase().endsWith('.ogg')) throw new Error('AUDIO_FILE must be OGG');

export const options = {
  scenarios: {
    clips: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1m',
      duration,
      preAllocatedVUs: vus,
      gracefulStop: '90s',
    },
  },
  thresholds: {
    clip_failed: [{ threshold: 'count==0', abortOnFail: false }],
    dropped_iterations: [{ threshold: 'count==0', abortOnFail: false }],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  if (!__ENV.BASE_URL || !__ENV.LOAD_TEST_TOKEN || !__ENV.AUDIO_FILE) {
    throw new Error('BASE_URL, LOAD_TEST_TOKEN, and AUDIO_FILE are required');
  }
  return { runId };
}

function recordFailure(stage, status) {
  failed.add(1, { stage, status: String(status) });
  if (errorsLogged++ < 2) console.error(`clip failed: stage=${stage} status=${status}`);
}

function ensureSession(data) {
  if (sessionId) return true;
  const id = `loadtest-${data.runId}-${exec.vu.idInTest}`;
  const response = http.post(
    `${__ENV.BASE_URL}/internal/load-test/sessions?sessionId=${id}`,
    null,
    {
      headers: { 'X-Load-Test-Token': __ENV.LOAD_TEST_TOKEN },
      tags: { name: 'POST /internal/load-test/sessions' },
      timeout: '15s',
    },
  );
  if (response.status !== 201) {
    recordFailure('session', response.status);
    return false;
  }
  sessionId = id;
  return true;
}

export default function (data) {
  if (!ensureSession(data)) return;
  const started = Date.now();
  const response = http.post(
    `${__ENV.BASE_URL}/internal/load-test/clips?sessionId=${sessionId}&durationSeconds=3.4`,
    audio,
    {
      headers: { 'X-Load-Test-Token': __ENV.LOAD_TEST_TOKEN, 'Content-Type': 'audio/ogg' },
      tags: { name: 'POST /internal/load-test/clips' },
      timeout: '90s',
    },
  );
  elapsed.add(Date.now() - started);
  if (response.status === 200) accepted.add(1);
  else recordFailure('clip', response.status);
}
