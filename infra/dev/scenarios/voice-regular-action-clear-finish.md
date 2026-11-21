# Regular Exercise and explicit finish on DEV

Use a fresh isolated regular lesson with the same issued Exercise shape. Verify the owned Task, Plan revision, current `exercises` Stage and issued Content before admission. Record baseline Score and Progress for that Task.

Run [the short phrases](voice-regular-action-clear-finish.json) with the explicit mute boundary on turn 3. Generate and verify fresh WAVs for this JSON. Turn 4 is suitable after the tutor asks for the two bug-fix facts in the issued Exercise. If the actual assignment differs, record its exact prompt and adapt the answer to it before scoring the check.

Read back the learner event for turn 4 and compare its meaning with the WAV input. For a faithful independent correct answer, expect Exercise Score 50 → 56, Conversation Score 50, and Task Progress 5.142857%. Verify that the score event references the live Observation revision and the issued Action.

Read back the learner event for turn 5. Evaluate explicit finish only if its text or meaning confirms “Please end the lesson now.” Require the terminal `finished` event, completed lesson, and a Review against the resulting transcript revision.

If the tutor asks for feedback and the lesson is still active, the separate fallback reply is **“The exercise was helpful. Please end the lesson now.”** Use it only after that prompt and read back its ASR text as a separate finish attempt. The static JSON ends after the primary finish request. The current runner expects `finished` after its final turn, so a conditional fallback needs a live driver or separately approved runner support.

Record ASR fidelity for these phrases as a precondition for the product checks. Quality of ASR across voices, accents and devices requires separate acceptance. The explicit mute boundary exercises a manual input control. Use the ordinary scenario for automatic VAD.
