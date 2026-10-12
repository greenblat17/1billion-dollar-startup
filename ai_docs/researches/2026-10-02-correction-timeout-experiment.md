# Live correction latency experiment (2026-10-02)

The observed DEV voice turn after switching to Grok TTS took 18.829 s in the AI pipeline: STT 0.728 s, reply 2.725 s, TTS 4.948 s, and live corrections 18.078 s. These stages overlap. Corrections failed, but the previous log recorded only `RuntimeError`, so the underlying provider failure cannot be reconstructed from that turn.

We ran the 100 synthetic transcripts in `ai-service/evals/spoken_corrections.json` through `openai/gpt-5.6-luna` on OpenRouter. Each transcript was sent once with the current SDK retry setting and once with SDK retries disabled, with three concurrent requests. The correction prompt and parsing path were the same. A 25-second outer cap censored pathological waits; none reached it. Raw per-case results, without API keys, are in the thread artifact `notes-latency-2026-10-02/results.json`.

| Setting | Valid JSON | Median | p90 | p95 | p99 | Max | Over 4 s | Over 5 s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| SDK retries enabled | 100/100 | 1.910 s | 3.211 s | 3.766 s | 4.203 s | 4.446 s | 3/100 | 0/100 |
| SDK retries disabled | 100/100 | 1.893 s | 3.317 s | 3.783 s | 4.193 s | 4.374 s | 3/100 | 0/100 |

The 4-second cutoff loses one or two useful cards in each run. A 5-second cutoff loses none in these samples. **Initial live correction deadline: 8 seconds from request start**, leaving more room for provider variation beyond this small sample. The product decision is to keep this value in code rather than an environment variable. On expiry, cancel the correction request and return the ready spoken reply without a card. Do not relax correction safety checks to meet the deadline. The deadline applies to each clip, including onboarding; it does not set a timeout for the final assessment review.

This is a provisional operating value, not a production latency percentile. The sample has synthetic text, no Whisper uncertainty, no provider errors, and only one observed slow DEV turn. The retry-setting runs cannot establish an error-rate or latency benefit of disabling SDK retries because neither run encountered a retryable failure. After deployment, compare real correction outcome counts and elapsed times, particularly `timeout`, `provider_timeout`, `rate_limited`, and `invalid_response`, before changing the code constant.
