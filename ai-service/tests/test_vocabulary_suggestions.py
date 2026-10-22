import pytest

from app.vocabulary_suggestions import _ground, _overlaps_errors, discover_vocabulary_suggestions


class UpgradeModel:
    def __init__(self, proposals=None, fail_on=None):
        self.proposals = proposals or {}
        self.fail_on = fail_on
        self.seen = []

    async def discover_vocabulary_upgrades(self, chunk):
        turns = tuple(item["turn"] for item in chunk)
        self.seen.extend(turns)
        if self.fail_on in turns:
            raise TimeoutError("one chunk failed")
        return [proposal for turn in turns for proposal in self.proposals.get(turn, [])]

    async def verify_vocabulary_upgrades(self, candidates):
        return {item["id"]: {"accepted": True, "reason": "Предложено более точное слово."}
                for item in candidates}


def proposal(turn, context, original, alternative, category="word_upgrade", benefit=3):
    return {"turn": turn, "context": context, "original": original,
            "alternative": alternative, "category": category,
            "explanation": "Это даёт более точное выражение.", "benefit": benefit}


def test_exact_grounding_and_local_span_rules():
    text = "We saw a big increase in costs."
    candidate = proposal(0, text, "big", "substantial")
    assert _ground(candidate, {0: text})["original"] == "big"
    assert _ground({**candidate, "turn": 1}, {0: text}) is None
    assert _ground({**candidate, "original": "ig"}, {0: text}) is None
    assert _ground({**candidate, "alternative": "a substantial amount"}, {0: text})["category"] == "expression_upgrade"
    assert _ground({**candidate, "category": "expression_upgrade"}, {0: text})["category"] == "word_upgrade"
    assert _ground({**candidate, "category": "unexpected"}, {0: text})["category"] == "word_upgrade"
    reasons = []
    assert _ground({**candidate, "original": "big increase"}, {0: text}, reasons)["category"] == "expression_upgrade"
    assert reasons == []


def test_category_comes_from_minimal_edit_not_model_label():
    cases = [
        ("We crossed the landscape.", "the landscape", "the scenery", "expression_upgrade",
         "landscape", "scenery", "word_upgrade"),
        ("We went to the gallery.", "went to", "attended", "expression_upgrade",
         "went to", "attended", "expression_upgrade"),
        ("It was really beautiful.", "really beautiful", "breathtaking", "word_upgrade",
         "really beautiful", "breathtaking", "expression_upgrade"),
    ]
    for context, source, target, label, expected_source, expected_target, expected_category in cases:
        result = _ground(proposal(0, context, source, target, label), {0: context})
        assert result is not None
        assert (result["original"], result["alternative"], result["category"]) == (
            expected_source, expected_target, expected_category)


def test_grounding_accepts_long_exact_context():
    text = " ".join(["I enjoy painting and reading"] * 9) + " when I have a chance."
    result = _ground(proposal(0, text, "when I have a chance", "when I get the opportunity",
                              "expression_upgrade"), {0: text})
    assert result is not None


def test_minimal_edit_keeps_unique_source_when_inner_word_repeats():
    text = "We crossed the landscape and discussed a landscape."
    result = _ground(proposal(0, text, "the landscape", "the scenery", "word_upgrade"), {0: text})
    assert result is not None
    assert result["original"] == "the landscape"
    assert result["category"] == "expression_upgrade"


def test_error_overlap_is_local_to_changed_words():
    text = "I did a decision after a big increase in costs."
    upgrade = _ground(proposal(0, text, "big", "substantial"), {0: text})
    assert upgrade is not None
    error = {"turn": 0, "wrong": text,
             "better": "I made a decision after a big increase in costs."}
    assert not _overlaps_errors(upgrade, [error])
    assert _overlaps_errors(upgrade, [{"turn": 0, "wrong": text,
        "better": "I did a decision after a substantial increase in costs."}])


@pytest.mark.asyncio
async def test_search_covers_all_chunks_and_keeps_success_when_one_fails():
    turns = [{"transcript": f"We saw a big increase in costs {i}." + " x" * 1500}
             for i in range(3)]
    model = UpgradeModel({2: [proposal(2, "We saw a big increase in costs 2.", "big", "substantial")]},
                         fail_on=1)
    result = await discover_vocabulary_suggestions(model, turns, [], flow="call")
    assert set(model.seen) == {0, 1, 2}
    assert len(result) == 1 and result[0]["category"] == "word_upgrade"


@pytest.mark.asyncio
async def test_five_each_limit_and_global_benefit_order():
    words = ["big", "good", "bad", "nice", "small", "fast"]
    turns = [{"transcript": f"It was a {word} change."} for word in words]
    phrases = [
        ("said no to", "turned down the"),
        ("looked into it", "investigated that issue"),
        ("talked about it", "discussed that topic"),
        ("gave up on", "abandoned that plan"),
        ("came back to", "revisited that idea"),
        ("thought about it", "considered that option"),
    ]
    turns += [{"transcript": f"We {source} after lunch."} for source, _ in phrases]
    proposals = {
        index: [proposal(index, turn["transcript"], words[index], "substantial", benefit=min(index + 1, 5))]
        for index, turn in enumerate(turns[:6])
    }
    for index, (source, target) in enumerate(phrases, start=6):
        proposals[index] = [proposal(index, turns[index]["transcript"], source, target,
                                     "expression_upgrade", benefit=min(index - 5, 5))]
    result = await discover_vocabulary_suggestions(UpgradeModel(proposals), turns, [], flow="onboarding")
    assert [item["category"] for item in result].count("word_upgrade") == 5
    assert [item["category"] for item in result].count("expression_upgrade") == 5
    assert all(item["original"] != "big" for item in result)


@pytest.mark.asyncio
async def test_trace_records_model_rejection_without_exposing_reason_to_learner():
    class RejectingModel(UpgradeModel):
        async def verify_vocabulary_upgrades(self, candidates):
            return {item["id"]: {"accepted": False, "reason": "Это слово не повышает уровень."}
                    for item in candidates}

    traces = []
    async def save_trace(entries):
        traces.append(entries)

    text = "We saw a big increase in costs."
    result = await discover_vocabulary_suggestions(
        RejectingModel({0: [proposal(0, text, "big", "substantial")]}),
        [{"transcript": text}], [], flow="call", trace_sink=save_trace)
    assert result == []
    assert traces[0][0]["status"] == "rejected"
    assert traces[0][0]["reason"] == "Это слово не повышает уровень."


@pytest.mark.asyncio
async def test_mislabeled_short_phrase_reaches_verifier_as_expression():
    class CapturingModel(UpgradeModel):
        def __init__(self, proposals):
            super().__init__(proposals)
            self.verified = []

        async def verify_vocabulary_upgrades(self, candidates):
            self.verified.extend(candidates)
            return await super().verify_vocabulary_upgrades(candidates)

    text = "It was really beautiful."
    model = CapturingModel({0: [proposal(0, text, "really beautiful", "breathtaking", "word_upgrade")]})
    trace = []
    async def capture(entries):
        trace.extend(entries)

    result = await discover_vocabulary_suggestions(
        model, [{"transcript": text}], [], flow="call", trace_sink=capture)
    assert model.verified[0]["category"] == "expression_upgrade"
    assert result[0]["category"] == "expression_upgrade"
    assert trace[0]["proposedCategory"] == "word_upgrade"
    assert trace[0]["category"] == "expression_upgrade"
