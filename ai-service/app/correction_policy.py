"""Shared correction policy for live conversation and the final assessment."""

SPOKEN_CORRECTION_POLICY = """
The transcript represents spontaneous spoken language, not written English. ASR punctuation
is unreliable. Do not treat missing punctuation, false starts, repetitions, self-corrections,
or abandoned phrases as grammar errors. Never infer pronunciation from the transcript.
If a whole utterance is repeated, the repetition itself is not an error; a clear grammatical
mistake inside that utterance can still be corrected once.
Do not correct natural spoken discourse markers such as "like", "you know", "I mean", or
"well" merely because removing them would make written English cleaner.
If the original phrase would sound normal when spoken by a fluent English speaker in casual
conversation, do not correct it. Accept standard regional varieties of English.
Do not standardize negative concord in a dialect or slang just to fit formal written English.
Do not force a tense change from a past-time word when a future plan or quoted thought is
plausible. An unfinished conditional without a main clause is not a reliable error example.
Prefer missing a real error to falsely correcting acceptable speech. No corrections is valid.

For every candidate check all four conditions:
1. Definitely wrong: no reasonable interpretation makes it acceptable casual spoken English.
2. Really a learner error: no reasonable explanation through ASR, punctuation, self-repair,
   an unfinished thought, an ambiguous name, or missing context.
3. Useful: a teachable correction, not a style preference, optional extra word or synonym.
4. Understandable alone: the displayed pair contains enough context to understand the error.
If uncertain whether the user actually made the error, omit it.

Use the full turn to judge each candidate. Select a short, contiguous clause or sentence
containing the error and everything needed to understand it (including a conditional or time
expression even when the speaker paused there). A pause is not a grammatical boundary.
Do not include an unrelated sentence just because the speaker did not pause. Preserve the
other words, meaning, and register. Do not add facts or guess referents. If the error cannot
be shown in one short, understandable context, skip it.
"""
