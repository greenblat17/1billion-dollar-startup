// Real end-to-end AI-service load test. No provider mocks.
// Run against DEV only. Each VU has its own session; one iteration is one
// POST /v1/clips followed by polling until the job succeeds or fails.
// Example:
//   k6 run -e BASE_URL=http://127.0.0.1:8090 \
//     -e AUDIO_FILE=/tmp/speaky-probe.wav -e RATE_RPM=30 -e DURATION=2m \
//     --summary-export=/tmp/speaky-30rpm.json load/real-clips.js
// AI_INTERNAL_TOKEN must be set in the environment. Never put it on the CLI.

import http from 'k6/http';
import exec from 'k6/execution';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const accepted = new Counter('clip_accepted');
const completed = new Counter('clip_completed');
const failed = new Counter('clip_failed');
const endToEnd = new Trend('clip_e2e', true);
const audio = open(__ENV.AUDIO_FILE, 'b');
const isOgg = __ENV.AUDIO_FILE.toLowerCase().endsWith('.ogg');
const rate = Number(__ENV.RATE_RPM || 30);
const duration = __ENV.DURATION || '2m';
const deadlineMs = Number(__ENV.JOB_DEADLINE_MS || 65000);
const runId = __ENV.RUN_ID || 'real-load';
const preAllocatedVUs = Number(__ENV.PREALLOCATED_VUS || Math.max(20, Math.ceil(rate / 60) * 25));
const pollSeconds = Number(__ENV.POLL_SECONDS || 0.5);
const abortOnFail = __ENV.ABORT_ON_FAIL !== 'false';
let sessionId;
let errorsLogged = 0;

if (!Number.isInteger(rate) || rate < 1 || rate > 600) {
  throw new Error('RATE_RPM must be an integer from 1 to 600 clips per minute');
}
if (!Number.isInteger(preAllocatedVUs) || preAllocatedVUs < 1) {
  throw new Error('PREALLOCATED_VUS must be a positive integer');
}
if (!Number.isFinite(pollSeconds) || pollSeconds <= 0) {
  throw new Error('POLL_SECONDS must be positive');
}

export const options = {
  scenarios: {
    clips: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1m',
      duration,
      preAllocatedVUs,
      gracefulStop: '75s',
    },
  },
  thresholds: {
    clip_failed: [{ threshold: 'count==0', abortOnFail, delayAbortEval: '15s' }],
    dropped_iterations: [{ threshold: 'count==0', abortOnFail, delayAbortEval: '15s' }],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  if (!__ENV.BASE_URL || !__ENV.AI_INTERNAL_TOKEN || !__ENV.AUDIO_FILE) {
    throw new Error('BASE_URL, AI_INTERNAL_TOKEN, and AUDIO_FILE are required');
  }
  return { runId };
}

function fail(reason, stage = 'client') {
  failed.add(1, { reason, stage });
  if (errorsLogged++ < 2) {
    console.error(`clip failed: stage=${stage} reason=${reason}`);
  }
}

function ensureSession(data) {
  if (sessionId) return true;
  const response = http.post(
    `${__ENV.BASE_URL}/v1/sessions`,
    JSON.stringify({ sessionId: `loadtest-${data.runId}-${exec.vu.idInTest}` }),
    {
      headers: { 'X-Internal-Token': __ENV.AI_INTERNAL_TOKEN, 'Content-Type': 'application/json' },
      tags: { name: 'POST /v1/sessions' },
      timeout: '15s',
    },
  );
  if (response.status !== 201) {
    fail(`session_http_${response.status}`);
    return false;
  }
  sessionId = response.json('sessionId');
  return true;
}

export default function (data) {
  if (!ensureSession(data)) return;
  const started = Date.now();
  const response = http.post(
    `${__ENV.BASE_URL}/v1/clips`,
    {
      sessionId,
      durationSeconds: '3.4',
      audio: http.file(audio, isOgg ? 'probe.ogg' : 'probe.wav', isOgg ? 'audio/ogg' : 'audio/wav'),
    },
    {
      headers: { 'X-Internal-Token': __ENV.AI_INTERNAL_TOKEN, 'X-Speaky-Load-Test': '1' },
      tags: { name: 'POST /v1/clips' },
      timeout: '15s',
    },
  );
  if (!check(response, { 'clip accepted': r => r.status === 202 })) {
    fail(`post_http_${response.status}`);
    return;
  }
  accepted.add(1);
  const jobId = response.json('jobId');
  if (!jobId) {
    fail('missing_job_id');
    return;
  }
  const deadline = started + deadlineMs;
  while (Date.now() < deadline) {
    const polled = http.get(`${__ENV.BASE_URL}/v1/clips/${jobId}`, {
      headers: { 'X-Internal-Token': __ENV.AI_INTERNAL_TOKEN },
      tags: { name: 'GET /v1/clips/{id}' },
      timeout: '10s',
    });
    if (polled.status !== 200) {
      fail(`poll_http_${polled.status}`);
      return;
    }
    const status = polled.json('status');
    if (status === 'ok') {
      completed.add(1);
      endToEnd.add(Date.now() - started);
      return;
    }
    if (status === 'error') {
      const error = polled.json('error') || {};
      fail(String(error.code || 'job_error'), String(error.stage || 'unknown'));
      endToEnd.add(Date.now() - started);
      return;
    }
    sleep(pollSeconds);
  }
  fail('job_timeout');
  endToEnd.add(Date.now() - started);
}
