from app.notes_diff import notes_from_rewrite


def test_identical_yields_no_notes() -> None:
    text = "Usually I walk on the weekend."
    assert notes_from_rewrite(text, text) == []


def test_question_do_support() -> None:
    original = "How I celebrated it? It's a great question."
    corrected = "How did I celebrate it? It's a great question."
    notes = notes_from_rewrite(original, corrected)
    assert len(notes) == 1
    start, _, better = notes[0].partition("|||")
    spliced = original.replace(start, better, 1)
    assert spliced == corrected
    assert "did" in better
    assert "How I celebrated" in start or "I celebrated" in start


def test_article_on_uncountable() -> None:
    original = "I think that is the useful feedback for our product."
    corrected = "I think that is useful feedback for our product."
    notes = notes_from_rewrite(original, corrected)
    assert notes == ["the useful|||useful"]


def test_paraphrase_yields_no_notes() -> None:
    original = "How I celebrated it? It's a great question."
    rewritten = "Celebrating feedback is worth discussing in more detail altogether."
    assert notes_from_rewrite(original, rewritten) == []


def test_insert_uses_neighbor_anchor() -> None:
    original = "I go home after work."
    corrected = "I go to home after work."
    notes = notes_from_rewrite(original, corrected)
    assert notes == ["go|||go to"]
    start, _, better = notes[0].partition("|||")
    assert original.replace(start, better, 1) == corrected


def test_caps_at_three_hunks() -> None:
    original = "I go to home and speak good and I has cats."
    corrected = "I go home and speak well and I have cats."
    notes = notes_from_rewrite(original, corrected)
    assert len(notes) == 3
