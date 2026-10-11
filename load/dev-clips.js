// DEV only. One iteration is one POST /v1/clips, then a poll until the job
// finishes so a provider error is visible. Peak arrival is 20 iterations per
// minute: the lower published Groq whisper-large-v3 RPM. Do not point this at
// prod. Do not call /health.
//
//   export AI_INTERNAL_TOKEN=...   # not printed, not committed
//   k6 run -e BASE_URL=http://127.0.0.1:PORT -e AUDIO_FILE=/tmp/speaky-probe.wav load/dev-clips.js
//
// The header X-Speaky-Load-Test: 1 keeps the clip out of live metrics, the
// journal, and dialogue. This file is not copied into the Redeploy images.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const clipE2e = new Trend('clip_e2e', true);
const clipFailed = new Counter('clip_failed');
const clipPosts = new Counter('clip_posts');

export const options = {
  scenarios: {
    clips: {
      executor: 'ramping-arrival-rate',
      startRate: 5,
      timeUnit: '1m',
      preAllocatedVUs: 10,
      maxVUs: 20,
      stages: [
        { target: 5, duration: '30s' },
        { target: 10, duration: '30s' },
        { target: 20, duration: '45s' },
      ],
    },
  },
  thresholds: {
    http_req_failed: [{ threshold: 'rate==0', abortOnFail: true, delayAbortEval: '10s' }],
    clip_failed: [{ threshold: 'count==0', abortOnFail: true }],
    clip_e2e: [{ threshold: 'p(95)<20000', abortOnFail: true, delayAbortEval: '20s' }],
  },
  summaryTrendStats: ['avg', 'p(95)', 'count'],
};

const audio = open(__ENV.AUDIO_FILE, 'b');

export function setup() {
  const token = __ENV.AI_INTERNAL_TOKEN;
  if (!token || !__ENV.BASE_URL) {
    throw new Error('BASE_URL and AI_INTERNAL_TOKEN are required');
  }
  const created = http.post(
    `${__ENV.BASE_URL}/v1/sessions`,
    JSON.stringify({ sessionId: 'loadtest-mvp' }),
    {
      headers: {
        'X-Internal-Token': token,
        'Content-Type': 'application/json',
      },
      tags: { name: 'POST /v1/sessions' },
    },
  );
  if (created.status !== 201) {
    throw new Error(`session create failed with status ${created.status}`);
  }
  return { sessionId: 'loadtest-mvp' };
}

export default function (data) {
  const started = Date.now();
  const posted = http.post(
    `${__ENV.BASE_URL}/v1/clips`,
    {
      sessionId: data.sessionId,
      durationSeconds: '2',
      audio: http.file(audio, 'probe.wav', 'audio/wav'),
    },
    {
      headers: {
        'X-Internal-Token': __ENV.AI_INTERNAL_TOKEN,
        'X-Speaky-Load-Test': '1',
      },
      tags: { name: 'POST /v1/clips' },
      timeout: '15s',
    },
  );
  clipPosts.add(1);
  if (!check(posted, { 'clip accepted': (response) => response.status === 202 })) {
    clipFailed.add(1);
    return;
  }
  const jobId = posted.json('jobId');
  const deadline = Date.now() + 60000;
  let status = 'pending';
  while (Date.now() < deadline) {
    const polled = http.get(`${__ENV.BASE_URL}/v1/clips/${jobId}`, {
      headers: { 'X-Internal-Token': __ENV.AI_INTERNAL_TOKEN },
      tags: { name: 'GET /v1/clips/{id}' },
      timeout: '10s',
      responseType: 'text',
    });
    if (polled.status !== 200) {
      clipFailed.add(1);
      return;
    }
    status = polled.json('status');
    if (status === 'ok' || status === 'error') {
      break;
    }
    sleep(0.5);
  }
  clipE2e.add(Date.now() - started);
  if (status !== 'ok') {
    clipFailed.add(1);
  }
}
