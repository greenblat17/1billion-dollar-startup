"""Shared correction policy for live conversation and the final assessment."""

SPOKEN_CORRECTION_POLICY = """
The transcript represents spontaneous spoken language, not written English. ASR punctuation
is unreliable. Do not treat missing punctuation, false starts, repetitions, self-corrections,
or abandoned phrases as grammar errors. Never infer pronunciation from the transcript.
Do not correct natural spoken discourse markers such as "like", "you know", "I mean", or
"well" merely because removing them would make written English cleaner.
If the original phrase would sound normal when spoken by a fluent English speaker in casual
conversation, do not correct it. Accept standard regional varieties of English.
Prefer missing a real error to falsely correcting acceptable speech. No corrections is valid.

For every candidate check all four conditions:
1. Definitely wrong: no reasonable interpretation makes it acceptable casual spoken English.
2. Really a learner error: no reasonable explanation through ASR, punctuation, self-repair,
   an unfinished thought, an ambiguous name, or missing context.
3. Useful: a teachable correction, not a style preference, optional extra word or synonym.
4. Understandable alone: the displayed pair contains enough context to understand the error.
If uncertain whether the user actually made the error, omit it.

Choose the smallest understandable utterance fragment, NOT the fewest tokens. Include the
subject, object or clause needed to show the contrast. Quote the original exactly, including
its existing punctuation; do not reconstruct what the speaker might have intended.
Change only the error, preserve meaning and register, and do not add facts or guess referents.
If a self-contained pair cannot be formed from the transcript, skip the candidate.
"""
