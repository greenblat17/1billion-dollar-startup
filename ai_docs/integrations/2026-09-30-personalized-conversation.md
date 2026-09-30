# Personalized conversation after onboarding

Scope: personal facts, fixed proficiency, and context for returning after an absence. No weakness curriculum, daily timer or automatic rescoring is introduced.

## Context shared across paths

`Personalization` prepares context for ordinary clip replies (including users exempt from onboarding) and `Keep talking` / the legacy continue action. `OnboardingService` connects this service to the clip pipeline. The continuation additionally receives the last eight dialogue messages so it can follow up on a concrete detail instead of restarting the interview.

The shared instructions prioritize the latest user statement and explicit requests such as “speak more simply”; remembered content is data, not instructions. Every spoken turn starts with a reaction to that statement. Speaky is an English teacher who loves teaching people to speak; when they ask about her, she answers from that, without inventing a shared job or hobby. If they asked something, the answer comes before her own question. A phrase that is not a question still cannot be skipped. Getting to know the person continues from the reaction and does not replace it. A remembered interest is used only when the reaction reaches it. Personal callbacks are optional and relevant, never compulsory every turn. Proficiency guides vocabulary, phrase length and complexity with an occasional small comprehensible stretch. The assistant does not disclose the scores or turn ordinary conversation into a lesson.

## Persistence and updates

`learner:{sessionId}` has no TTL and stores person information, proficiency, the assessment run ID and `lastConversationAt` (UTC epoch seconds). It is independent of the rolling 40-message dialogue and the current onboarding attempt; `/onboarding` does not erase it. Memory store supports the same behavior in tests.

Person contains name, work, leisure, English goal and at most 12 short facts. Only explicitly stated information is extracted. Contradictions replace old facts; requests to forget remove them. Fields are schema/size validated; this is model extraction, not a proof that every fact is correct. Data never becomes system instructions. Sensitive traits are not inferred. Extraction consumes existing memory plus user transcripts, not assistant-generated claims.

A completed onboarding seeds the person from its transcripts and proficiency from the stored overall CEFR/position and per-skill band/position/score/confidence. Repeating the same completion does not reseed. A newly completed assessment may replace proficiency. An ordinary voice never changes it. A sealed practice call may move the stored scores by at most one shade, and that stepped snapshot is what the next conversation reads. Completed legacy attempts are lazily imported, including their transcript details. Old completed attempts are imported before explicit reset. A legacy summary without its original attempt can supply proficiency but cannot recover personal facts.

After STT and context preparation, the bounded five-second personal-fact extraction runs alongside the reply and correction calls. It does not write memory yet. Only after successful reply synthesis does the pipeline save extracted facts and `lastConversationAt`; a failed clip or cancelled task cannot mark a successful contact. On extraction failure, old facts are preserved and last contact is still recorded. If an assessment changed the profile during extraction, the current turn is extracted again against those newer facts before saving. Redis/context failures fall back to general conversation instructions. Current user words and recent dialogue override stale memory even if extraction fails. The existing single-process serialized Telegram flow remains the concurrency model.

## Returning and streak

Context is read before recording the new turn. It includes the current displayed streak, previous contact timestamp and calendar-day distance in Europe/Moscow. At two or more calendar days, the first response may briefly welcome the user back. Exact day counts are optional; no guilt, invented events or claims of missing the user. This is calendar-day distance, not the number of missed practice days.

Successful contact consumes the return cue for later replies. `Keep talking` suppresses absence greetings and marks contact without incrementing streak or generating new personal facts from the button click. No reliable timestamp exists for legacy history, so the first migrated response does not invent an absence. Silence/clarification does not update personal memory or its contact timestamp. Existing streak calculation is unchanged.

## Validation

Tests cover person replacement, fixed proficiency, memory/Redis persistence, no TTL, repeat greeting suppression, continuation context and recent history, legacy import, reset preservation, invalid model JSON and provider failure fallback. Real-model extraction and style quality still require live evaluation; local tests use controlled models. No deployment or live Telegram test is part of this implementation.
