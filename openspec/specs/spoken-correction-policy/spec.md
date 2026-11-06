# spoken-correction-policy Specification

## Purpose
Describes which spoken corrections are shown to the user and which are discarded. It applies to all
clip conversations: the ordinary Telegram dialogue, the voice steps of onboarding, and the final
assessment. Sources: `ai_docs/integrations/2026-09-30-spoken-corrections.md`, code
`ai-service/app/correction_policy.py`.

## Requirements

### Requirement: Precision over recall
The policy SHALL prefer a missed error over a false correction of normal conversational English.

#### Scenario: Four checks must pass
- **WHEN** a candidate correction is evaluated
- **THEN** it is shown only if it is definitely wrong, plausibly the learner's own error rather than an
  ASR or spoken-language artifact, useful to correct, and understandable without surrounding context
- **AND** ASR punctuation, false starts, repetitions, self-repairs, abandoned phrases and discourse
  markers are not treated as grammar errors

#### Scenario: Model decision flags
- **WHEN** a candidate carries decision flags
- **THEN** confidence must be `high`, and `definitely_wrong`, `worth_showing`, `understandable_alone`
  must be boolean `true`
- **AND** `is_spoken_language_artifact` and `is_asr_uncertain` must be boolean `false`
- **AND** a missing or wrongly typed field, an unknown kind, or an unchanged pair discards the candidate

### Requirement: Grounded context match
A correction SHALL be built on an exact context match from the transcript with a unique error inside it.

#### Scenario: Whole-word match
- **WHEN** the model returns `context`, `error` and `replacement`
- **THEN** the code requires a whole-word context match and a unique error inside that context
- **AND** context is limited to 30 words
- **AND** straight and curly apostrophes are part of the word, so `can` cannot match inside `can't` or
  `can’t`

#### Scenario: Overlapping or ambiguous candidates
- **WHEN** a candidate overlaps a marked speech artifact or is ambiguous
- **THEN** it is discarded
- **AND** identical repeated contexts receive the same edit

### Requirement: Display unit
The displayed unit SHALL be a short continuous clause or sentence taken from the full transcript.

#### Scenario: Telegram rendering
- **WHEN** a correction is shown in Telegram
- **THEN** only the changed words are struck through
- **AND** the bold replacement is placed beside them inside the context

#### Scenario: Explanation is optional
- **WHEN** a `reason` is offered as a displayable explanation
- **THEN** it is kept only when it is one line, one sentence, at most 160 characters, and not a generic
  phrase
- **AND** a poor explanation is omitted while the accepted correction remains

### Requirement: Alternative candidates are a separate kind
`alternative` SHALL describe an already correct phrase as a separate learning item, not as an error.

#### Scenario: Alternative gate
- **WHEN** the kind is `alternative`
- **THEN** `definitely_wrong` must be `false` while `already_correct` and `meaning_preserved` are `true`
- **AND** the saved `wrong` field is the correct phrase, not a claim of error
- **AND** the alternative card does not strike through the original

### Requirement: Final assessment re-verification
The final assessment SHALL check the saved candidates with a separate verifier; an earlier accepted
decision is not proof of correctness.

#### Scenario: Grouped verification with a deadline
- **WHEN** candidates are verified
- **THEN** they are divided into groups of five and up to three groups run concurrently under one
  10-second deadline
- **AND** verified groups keep their examples when another group fails or times out
- **AND** unverified pairs are never shown

#### Scenario: Selection limits
- **WHEN** the assessment selects examples for onboarding or a practice call
- **THEN** it selects at most five error examples for Grammar, five for Vocabulary and three optional
  alternatives
- **AND** completed cached reports are not regenerated
