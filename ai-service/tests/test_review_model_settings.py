from app.config import Settings


def test_review_related_defaults_do_not_use_blocked_google_route(monkeypatch):
    for name in ("ONBOARDING_REVIEW_MODEL", "CORRECTION_VERIFICATION_MODEL", "VOCABULARY_VERIFICATION_MODEL", "PERSON_MEMORY_MODEL"):
        monkeypatch.delenv(name, raising=False)
    settings = Settings.from_env()
    assert settings.onboarding_review_model == "openai/gpt-4o-mini"
    assert settings.correction_verification_model == "openai/gpt-4o-mini"
    assert settings.vocabulary_verification_model == "openai/gpt-5.6-luna"
    assert settings.person_memory_model == "openai/gpt-4o-mini"


def test_review_related_models_can_be_configured(monkeypatch):
    monkeypatch.setenv("ONBOARDING_REVIEW_MODEL", "review/model")
    monkeypatch.setenv("CORRECTION_VERIFICATION_MODEL", "verifier/model")
    monkeypatch.setenv("VOCABULARY_VERIFICATION_MODEL", "vocabulary/model")
    monkeypatch.setenv("PERSON_MEMORY_MODEL", "memory/model")
    settings = Settings.from_env()
    assert settings.onboarding_review_model == "review/model"
    assert settings.correction_verification_model == "verifier/model"
    assert settings.vocabulary_verification_model == "vocabulary/model"
    assert settings.person_memory_model == "memory/model"
