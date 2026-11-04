"""Content-free, idempotent facts for the Vocabulary Builder dashboard."""

from __future__ import annotations

from uuid import uuid4

STREAM = "vocabulary:analytics:v1"
PHASES = {"Understand", "Use", "Recall", "Word game"}
CATEGORIES = {"understood", "confused", "conversation", "used", "natural_without_target",
              "misuse", "correct", "incorrect", "give_up", "uncertain", "not_heard",
              "recalled", "not_recalled"}


def state_facts(session_id: str, before: dict, after: dict, at: float) -> list[dict]:
    facts = []
    old_items = {item["id"]: item for item in before["items"]}
    new_items = {item["id"]: item for item in after["items"]}
    for item_id, item in new_items.items():
        if item_id not in old_items:
            raw_source = item.get("source", "")
            source = ("manual" if raw_source == "manual" else "review_example" if ":example:" in raw_source
                      else "review_suggestion" if ":suggestion:" in raw_source else "review")
            facts.append(_fact(session_id, at, "word_saved", f"word:{session_id}:{item_id}:saved",
                               itemId=item_id, source=source))
        elif (old_items[item_id].get("reviewDueAt") != item.get("reviewDueAt") and
              item.get("reviewDueAt") and (after.get("lesson") or {}).get("format", 0) >= 5 and
              (after.get("lesson") or {}).get("status") == "finished"):
            facts.append(_fact(session_id, at, "word_due", f"word:{session_id}:{item_id}:due:{item['reviewDueAt']}",
                               itemId=item_id, dueAt=item["reviewDueAt"]))
    for item_id in old_items.keys() - new_items.keys():
        facts.append(_fact(session_id, at, "word_deleted", f"word:{session_id}:{item_id}:deleted",
                           itemId=item_id))
    old_lesson, new_lesson = before.get("lesson"), after.get("lesson")
    if new_lesson and (not old_lesson or old_lesson["id"] != new_lesson["id"]):
        lesson_id = new_lesson["id"]
        facts.append(_fact(session_id, at, "lesson_created", f"lesson:{lesson_id}:created",
                           lessonId=lesson_id, format=new_lesson.get("format", 3),
                           wordCount=len(new_lesson.get("words", [])),
                           words=[{"itemId": word["id"],
                                   "source": "manual" if word.get("source") == "manual" else "review",
                                   "reviewDue": bool(word.get("reviewDueAt") and at >= word["reviewDueAt"])}
                                  for word in new_lesson.get("words", [])]))
    if old_lesson and new_lesson and old_lesson["id"] == new_lesson["id"]:
        if old_lesson.get("status") != "finished" and new_lesson.get("status") == "finished":
            complete = (old_lesson.get("status") == "cards" and
                        len(new_lesson.get("cardResults", {})) == len(new_lesson.get("words", [])))
            facts.append(_fact(session_id, at, "lesson_finished", f"lesson:{new_lesson['id']}:finished",
                               lessonId=new_lesson["id"], category="complete" if complete else "partial",
                               position=old_lesson.get("index", 0)))
    return facts


def trace_facts(session_id: str, lesson: dict, events: list[dict]) -> list[dict]:
    facts = []
    for event in events:
        kind = event.get("type")
        if kind not in {"answer", "transition", "hint", "spoken"}:
            continue
        category = event.get("category", "")
        phase = event.get("phase", "")
        fact = _fact(session_id, event["at"], kind, f"trace:{lesson['id']}:{event['eventId']}",
                     lessonId=lesson["id"], itemId=event.get("wordId"),
                     phase=phase if phase in PHASES else "",
                     category=category if category in CATEGORIES else "",
                     position=event.get("fromIndex", lesson.get("index", 0)),
                     turnId=event.get("turnId"),
                     hinted=bool(event.get("hinted", False)))
        facts.append(fact)
    return facts


def _fact(session_id: str, at: float, kind: str, event_id: str, **fields) -> dict:
    return {"id": event_id, "sessionId": session_id, "at": at, "kind": kind, **fields}


def error_fact(session_id: str, lesson_id: str, message_id: int, stage: str, error_type: str,
               at: float) -> dict:
    event_id = (f"error:{session_id}:{lesson_id}:{message_id}:{stage}:{error_type}"
                if message_id > 0 else f"error:{uuid4().hex}")
    return _fact(session_id, at, "error", event_id, lessonId=lesson_id,
                 messageId=message_id, phase=stage[:32], category=error_type[:48])
