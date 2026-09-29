# Conversation memory

Status: steps 1–3 implemented on `feature/onboarding-memory`. A later pass still has to update the document from ordinary replies.

## What is stored

`memory:{sessionId}` is separate from `onboarding:{sessionId}` and from `session:{id}`. It has no TTL. Without Redis, tests keep the same JSON in process memory.

```text
slots.name / work / leisure / goal / cefr   {value, updatedAt} or absent
threads                                      [] until the next pass
lastTalkAt                                   null until the next pass
```

Onboarding completion writes `work`, `leisure`, `goal`, and `cefr` from the finished attempt. A known Telegram display name, already stored by `record_profile`, fills `name`. An empty Telegram name does not clear a name already in the document. Threads are left as they are.

`/onboarding` still replaces the onboarding attempt immediately. It does not delete this document. The next completion replaces the four onboarding slots and the Telegram name when one is present.

The first ordinary voice after completion, if the memory key is missing, copies the saved attempt once. Later voices only read it, so a future update from the conversation is not overwritten by the frozen onboarding profile.

## How a reply uses it

`ClipPipeline.run` loads the document and passes `render_note` to the reply model. Notes for the on-screen correction do not see it. No document means no note, which is the case for chats that have not finished onboarding.

The note says the facts are data, not instructions, and that the level must not be spoken. A name line is included only when the slot is filled. Empty checklist slots render as `unknown`.

## Not in this pass

There is no extraction after an ordinary turn. Threads stay empty, `lastTalkAt` is not written, and the document does not change because someone mentioned a new job or a first week at university. That patch, and the tests for replace / open / close / empty patch, are the next step.
