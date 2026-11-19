import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("foundation-check-remote.py")
SPEC = importlib.util.spec_from_file_location("foundation_check_remote", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class Response(io.BytesIO):
    status = 200


class Opener:
    def __init__(self, response):
        self.response = response
        self.requests = []

    def open(self, request, timeout):
        self.requests.append(request)
        return Response(json.dumps(self.response).encode())


class FoundationCheckRemoteTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.env = self.work / "dev.env"
        self.env.write_text("SPEAKY_ENV=dev\nAGENT_TEST_ENABLED=true\nAI_INTERNAL_TOKEN=secret-local-only\nSERVER_PORT=8443\n")
        self.student = "00000000-0000-0000-0000-000000000001"

    def test_regular_entitlement_returns_only_safe_fields(self):
        (self.work / "foundation-input.json").write_text(json.dumps({"mode": "regular_entitlement", "studentId": self.student}))
        opener = Opener({"status": "created", "entitlementId": "receipt-id", "validUntil": "tomorrow",
                         "token": "must-not-appear", "scope": "must-not-appear"})
        result = MODULE.run(self.work, env_path=self.env, opener=opener)
        self.assertEqual(self.student, result["studentId"])
        self.assertEqual({"status": "created", "entitlementId": "receipt-id", "validUntil": "tomorrow"},
                         result["steps"][0]["response"])
        self.assertTrue(opener.requests[0].full_url.endswith("/regular-entitlement"))
        self.assertNotIn("secret-local-only", json.dumps(result))

    def test_repo_scenario_keeps_topic_scope_but_redacts_auth_scope(self):
        (self.work / "foundation-input.json").write_text(json.dumps({"mode": "scenario", "studentId": self.student,
                                                          "scenario": "foundation-read.json"}))
        (self.work / "foundation-read.json").write_text(json.dumps({"steps": [
            {"name": "catalog", "request": {"kind": "catalog", "args": {}}}]}))
        opener = Opener({"result": {"topics": [{"scope": "Educational depth and boundaries"}],
                                     "executionScope": "must-not-appear"}})
        result = MODULE.run(self.work, env_path=self.env, opener=opener)
        self.assertEqual("Educational depth and boundaries", result["steps"][0]["response"]["result"]["topics"][0]["scope"])
        self.assertNotIn("must-not-appear", json.dumps(result))
        self.assertTrue(opener.requests[0].full_url.endswith("/foundation-check"))

    def test_rejects_scenario_path_escape(self):
        (self.work / "foundation-input.json").write_text(json.dumps({"mode": "scenario", "studentId": self.student,
                                                          "scenario": "../private.json"}))
        with self.assertRaisesRegex(ValueError, "invalid_scenario"):
            MODULE.run(self.work, env_path=self.env, opener=Opener({}))

    def test_resolves_source_and_replay_from_prior_step(self):
        previous = {"source": {"response": {"result": {"sources": [{"type": "event", "id": "event-id"}]}}},
                    "update": {"request": {"kind": "command", "operationId": "same-id"}}}
        self.assertEqual([{"type": "event", "id": "event-id"}],
                         MODULE.resolve({"$ref": "source.response.result.sources"}, previous))
        self.assertEqual({"kind": "command", "operationId": "same-id"},
                         MODULE.resolve({"$ref": "update.request"}, previous))
        self.assertEqual(36, len(MODULE.resolve({"$uuid": True}, previous)))


if __name__ == "__main__":
    unittest.main()
