# Final Vocabulary enrichment: initial real-dialogue probe

Date: 2026-10-21. Local code is not deployed. Three retained, adult-looking
dialogues were sampled read-only from production Redis: two practice calls
(8 and 7 learner turns) and one onboarding (5 turns). Conversations with
contact information were excluded. An isolated script sent the current local
discovery and verifier prompts to the configured provider. It did not change
Redis, chat messages, assessments, or deployment state. This is an exploratory
quality probe, not a representative benchmark. Audio was not reviewed.

The first prompt version proposed 12 pairs across the three dialogues. The
isolated verifier accepted four. Discovery often labelled a two-word source
as `word_upgrade`, which the local exact-span gate would reject. It also
proposed `went to the championship` → `competed in the championship`; the
verifier accepted it despite the change from attendance to participation.

The prompt was revised to show exact one-word JSON spans and to reject added
participation or other unsupported meaning. A second run on the same three
dialogues proposed 10 pairs (7, 0, 3); the verifier accepted six (5, 0, 1).
The pair `sportsman` → `athlete` was accepted as a higher-level word, though a
clear level gain is doubtful. `one step` → `with a stopover` and `rich` →
`nuanced` also require context and audio review before treating them as useful.
Other accepted options included `interesting` → `stimulating` and
`got satisfaction` → `derived satisfaction`. Some rejected pairs were sensible;
model judgment is unstable on this sample. These counts are from the isolated
prompt probe before the exact local parser, per-category quotas, and Telegram
selection. They are not displayed-correction counts.

Conclusion: the split workflow and API requests execute on real dialogue, but
this small probe does not establish the quality threshold for deployment. The
same-model verifier can approve a meaning or level shift. Before deployment,
run the exact local pipeline on a larger annotated sample and a separate
held-out sample, inspect complete context and disputed audio, and count useful,
missed, and falsely approved suggestions per category. Do not tune on the
held-out sample. The local test suite proves contract and failure behavior,
not model judgment.

A separate isolated live-prompt check used three synthetic learner turns with the
current local prompt. The model returned `natural` for `did a decision` and
`made a photo`, `grammar` for `I am agree`, and no note for the natural
phrase `a big increase`. This checks prompt direction only; it does not measure
recall on real speech or guarantee the parser accepts every model response.

## Follow-up with decision reasons (2026-10-22)

Ran the exact local discovery, grounding, verifier, and selection pipeline on
four recent retained conversations sampled read-only from production Redis:
two calls (8 and 5 learner turns) and two onboardings (5 and 3 turns).
Conversations containing obvious contact details were excluded. The local
code called the configured OpenRouter endpoint; it did not update production
Redis, send Telegram messages, or deploy code. Audio was not checked.

Two consecutive runs on the same four dialogues differed. The first produced
20 proposals: one selected, 15 rejected by the verifier, four rejected by
local grounding. The second produced 21: none selected, 14 verifier rejections,
seven grounding rejections. The first selected `interesting` → `stimulating`
in “very interesting conversation with you”; its claimed intellectual nuance
is uncertain from the transcript. The second search proposed `very interesting`
→ `highly engaging` instead, which the verifier rejected for naturalness and
grammar. The candidate set is not stable even at temperature zero.

Specific verifier reasons were useful. It rejected `satisfaction` →
`fulfillment` for adding depth of feeling, `go abroad` → `study abroad` for
adding a purpose, `important` → `prevalent` for changing meaning, and
`boost` → `enhance` for no clear gain or unnatural context. It rejected
`God bless your night` → `Have a blessed night` because that would repair an
unnatural phrase rather than enrich correct Vocabulary.

The local filter rejected `really beautiful` → `stunning` and `the landscape`
→ `the scenery` because the model labelled two-word spans as `word_upgrade`.
It rejected `went to` → `attended` because `expression_upgrade` requires at
least two target words. It rejected two proposals in a 55-word onboarding
turn because the model copied more than the 35-word context limit. The trace
now records these specific local rules instead of a generic invalid-source
message. This shows a strict formatting/grounding loss in addition to the
verifier's semantic decisions; it does not establish which rejected proposals
would be useful after correction.

After this probe, the local parser was changed to infer category from the
minimal edit, accept short edits with different word counts, and allow exact
long contexts. The counts above describe the earlier parser and have not been
relabelled as results of the revised parser.

The revised parser was run again on the same four dialogues. Discovery returned
20 proposals: three selected, 16 rejected by the verifier, and one outside
the four-word source limit. This is a fresh model search, so the change from
the preceding counts cannot be attributed only to parser rules. The selected
pairs were `step` → `stop`, `interesting` → `engaging`, and `beautiful` →
`picturesque`. The verifier's own reason called `step` erroneous, yet accepted
it as optional enrichment. That is a quality-gate contradiction: a source
error belongs in live correction, not in final upgrades. The other two pairs
need human context review for a real level gain and preserved meaning. The
local parser change is functional, but these results still block deployment.
