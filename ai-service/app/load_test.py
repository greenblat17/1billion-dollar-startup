"""Mark a clip as a load-test session so it does not enter live monitoring."""

from __future__ import annotations

from contextvars import ContextVar, Token

_active: ContextVar[bool] = ContextVar("speaky_load_test", default=False)


def bind_load_test(active: bool) -> Token[bool]:
    return _active.set(active)


def reset_load_test(token: Token[bool]) -> None:
    _active.reset(token)


def load_test_active() -> bool:
    return _active.get()


def load_test_requested(value: str | None) -> bool:
    return (value or "").strip() == "1"
