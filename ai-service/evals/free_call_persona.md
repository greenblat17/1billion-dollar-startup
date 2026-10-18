# Friendly Buddy: paired live-dialogue check

This evaluation compares the existing `ordinary` reply prompt with the new `free_call` prompt through the real `OpenAiChatModel`. It does not call STT, TTS, Telegram, or a fake LLM. The 12 fictional scenario cards are in `free_call_persona_scenarios.json`.

## Run one scenario

From `ai-service/`, with Python 3.12, installed `requirements-dev.txt`, and the same `OPENAI_BASE_URL` and `LLM_MODEL` as the target DEV environment:

```bash
PYTHONPATH=. python evals/run_free_call_persona.py --list
PYTHONPATH=. python evals/run_free_call_persona.py \
  --live --scenario workday --turns 5 --output-dir /tmp/speaky-free-call-eval
```

`OPENAI_API_KEY` must already be in the process environment through the normal secret mechanism. The script refuses to call the provider without `--live` or that key. It requires only the LLM credential; Groq, TTS, Redis, and `AI_INTERNAL_TOKEN` are unnecessary. Do not paste a credential into a command, scenario, transcript, or report.

The first user line is fixed by the scenario. For later lines, a human tester follows the same scenario brief in each variant and responds naturally to Speaky's actual answer. Both variants use the same model, temperature (`0.7`), synthetic profile, opening, and turn limit. Their order is randomized; `--seed` can reproduce the order. At the default five turns, 12 cards make 120 reply calls before any provider retries. Stop with Ctrl+C to save a partial run.

The script writes two private local JSON files. Give the `-blind.json` transcript to evaluators; keep `-key.json` closed until scoring is complete. The blind file contains both conversations, per-turn wall time, and provider-reported token counts when available. A model error and incomplete variant are recorded explicitly. These are synthetic prompts, but anything the tester types is saved, so use fictional details only.

## Score before opening the key

For each variant, assess the seven acceptance points from the PDF:

1. Does the exchange avoid a repeating reaction → question pattern?
2. Does Speaky add useful thoughts after short answers?
3. Does its character stay recognizable and consistent?
4. Does it let understandable language mistakes pass during conversation?
5. Does it offer a concrete way forward when the user is stuck or bored?
6. Does it leave room for the user instead of monologuing?
7. Does it use actual history without inventing personal events?

Also give a 1–5 rating for naturalness/interest, choose which variant you would talk to again, and record one or two exact turns explaining the choice. A lower question count alone is not a win. Mark a criterion **not observed** if the scenario did not exercise it; do not turn that into a pass. Compare latency and token usage alongside quality, without treating the 12 cases as a capacity test.

The two conversations can diverge because the human answers each Speaky turn. This is a qualitative comparison under the same brief, not a controlled claim about a single utterance. Repeat close cases or use more than one evaluator. After this model check, verify selected conversations through Telegram in DEV and then test preference with real users. Neither this script nor local unit tests prove live voice quality or deployment.
