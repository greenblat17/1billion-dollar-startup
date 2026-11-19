"""Offline guards for the DEV catalog operator runner."""

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SOURCE = Path(__file__).with_name("catalog-release-remote.py")
spec = importlib.util.spec_from_file_location("catalog_release_remote", SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CatalogReleaseRemoteTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.work = Path(self.temp.name)
        self.raw = b'{"version":"speaky-v55-v2"}'
        (self.work / "catalog.json").write_bytes(self.raw)
        self.input = {"version": "speaky-v55-v2", "sha256": hashlib.sha256(self.raw).hexdigest(),
                      "publish": False, "reviewer": "", "reviewReference": ""}

    def save(self):
        (self.work / "catalog-input.json").write_text(json.dumps(self.input))

    def test_hash_mismatch_stops_before_any_dev_connection(self):
        self.input["sha256"] = "0" * 64
        self.save()
        with patch.object(module, "inspect_dev") as inspect, self.assertRaisesRegex(RuntimeError, "catalog_sha256_mismatch"):
            module.release(self.work)
        inspect.assert_not_called()

    def test_publish_requires_specific_human_review_reference(self):
        self.input["publish"] = True
        self.save()
        with patch.object(module, "inspect_dev") as inspect, self.assertRaisesRegex(RuntimeError, "catalog_review_required"):
            module.release(self.work)
        inspect.assert_not_called()

    def test_import_only_never_calls_publish(self):
        self.save()
        with patch.object(module, "inspect_dev", return_value=({"AI_INTERNAL_TOKEN": "private"}, "443")), \
             patch.object(module, "read_current", return_value=None), \
             patch.object(module, "run_checked") as command, \
             patch.object(module.subprocess, "run"):
            result = module.release(self.work)
        self.assertEqual(result["published"], False)
        self.assertEqual([call.args[1] for call in command.call_args_list],
                         ["catalog_copy", "catalog_validate", "catalog_import"])

    def test_publish_reads_current_version_after_write(self):
        self.input.update(publish=True, reviewer="Alex", reviewReference="https://example.com/accepted")
        self.save()
        current = {"version": "speaky-v55-v2", "publishedAt": "2026-11-17T00:00:00Z",
                   "digest": "digest", "topics": [{"id": "one"}]}
        with patch.object(module, "inspect_dev", return_value=({"AI_INTERNAL_TOKEN": "private"}, "443")), \
             patch.object(module, "read_current", side_effect=[None, current]), \
             patch.object(module, "run_checked") as command, \
             patch.object(module.subprocess, "run"):
            result = module.release(self.work)
        self.assertEqual(result["topicCount"], 1)
        self.assertEqual([call.args[1] for call in command.call_args_list][-1], "catalog_publish")


if __name__ == "__main__":
    unittest.main()
