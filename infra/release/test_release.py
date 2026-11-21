import argparse
import io
import json
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

import release
import rollout

SHA = "1" * 40
DIGEST = "2" * 64
COMPONENTS = ("ktor", "ai", "miniapp")


def sample():
    return {
        "schema_version": 1, "source_sha": SHA, "runtime_family": "foundation",
        "build_run_id": 10, "build_run_attempt": 2,
        "images": {part: {"platform": "linux/amd64", "image_id": f"sha256:{DIGEST}",
                         "sha256": DIGEST, "artifact_id": index,
                         **({"edge_app_sha256": DIGEST} if part == "miniapp" else {})}
                   for index, part in enumerate(COMPONENTS, start=30)},
    }


def archive_info():
    return [{"id": index, "name": f"speaky-{part}-2", "expired": False}
            for index, part in enumerate(COMPONENTS, start=30)]


class ManifestTest(unittest.TestCase):
    run_info = {"id": 10, "run_attempt": 2, "event": "push", "head_sha": SHA}

    def test_partial_selection_is_canonical_and_prod_has_two_components(self):
        self.assertEqual(release.select_components("dev", "miniapp, ai"), ("ai", "miniapp"))
        self.assertEqual(release.select_components("prod", "all"), ("ktor", "ai"))
        for requested in ("", "ai,ai", "ai,miniapp", "other"):
            with self.subTest(requested=requested), self.assertRaises(ValueError):
                release.select_components("prod", requested)

    def test_only_current_attempt_and_expected_artifact_ids_are_valid(self):
        release.validate_manifest(sample(), self.run_info, ("ai",), archive_info())
        for field, value in (("build_run_attempt", 1), ("build_run_id", 11),
                             ("source_sha", "a" * 40), ("runtime_family", "legacy")):
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.validate_manifest({**sample(), field: value}, self.run_info, ("ai",),
                                          archive_info())
        bad_artifact = archive_info()
        bad_artifact[1]["expired"] = True
        with self.assertRaisesRegex(ValueError, "expired"):
            release.validate_manifest(sample(), self.run_info, ("ai",), bad_artifact)
        wrong_id = archive_info()
        wrong_id[1]["id"] = 99
        with self.assertRaisesRegex(ValueError, "artifact ID"):
            release.validate_manifest(sample(), self.run_info, ("ai",), wrong_id)

    def test_build_push_matches_exact_source_but_manual_build_accepts_a_candidate(self):
        metadata = {**self.run_info, "head_sha": "f" * 40}
        with self.assertRaisesRegex(ValueError, "push SHA"):
            release.validate_manifest(sample(), metadata, ("ktor",), archive_info())
        release.validate_manifest(sample(), {**metadata, "event": "workflow_dispatch"},
                                  ("ktor",), archive_info())

    def test_dev_receipt_must_cover_the_selected_payload_and_attempt(self):
        build = sample()
        receipt = {**build, "environment": "dev", "images": {"ai": build["images"]["ai"]}}
        self.assertTrue(release.receipt_matches(receipt, build, ("ai",)))
        self.assertFalse(release.receipt_matches(receipt, build, ("ai", "ktor")))
        self.assertFalse(release.receipt_matches({**receipt, "build_run_attempt": 1}, build, ("ai",)))
        different_bytes = {"ai": {**receipt["images"]["ai"], "sha256": "3" * 64}}
        self.assertFalse(release.receipt_matches({**receipt, "images": different_bytes}, build, ("ai",)))

    def test_fetch_rejects_corrupt_bytes_without_a_local_manifest(self):
        build = sample()
        build["images"]["ai"]["sha256"] = release.hashlib.sha256(b"archive").hexdigest()

        class GitHub:
            def __init__(self, _repository):
                pass

            def run(self, _run, _workflow, **_options):
                return ManifestTest.run_info

            def artifacts(self, _run):
                return [*archive_info(), {"id": 50, "name": "speaky-release-2", "expired": False}]

            def download(self, artifact, member, maximum=None, output=None):
                if member == "release.json":
                    return build
                output.write_bytes(b"corrupt")

        with tempfile.TemporaryDirectory() as directory, patch.object(release, "GitHub", GitHub):
            directory = Path(directory)
            args = argparse.Namespace(repo="owner/repo", environment="dev", components="ai",
                                      run=10, directory=directory)
            with self.assertRaisesRegex(ValueError, "image archive checksum mismatch"):
                release.fetch(args)
            self.assertFalse((directory / "release.json").exists())

    def test_github_zip_digest_and_members_are_checked_before_extraction(self):
        github = release.GitHub("owner/repo")
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("release.json", json.dumps(sample()))
        data = buffer.getvalue()

        def download(_args, *, stdout, check):
            self.assertTrue(check)
            stdout.write(data)

        with patch.object(subprocess, "run", side_effect=download):
            current = {"id": 30, "digest": f"sha256:{release.hashlib.sha256(data).hexdigest()}"}
            self.assertEqual(github.download(current, "release.json", maximum=65536), sample())
            with self.assertRaisesRegex(ValueError, "checksum"):
                github.download({**current, "digest": f"sha256:{DIGEST}"},
                                "release.json", maximum=65536)
            with self.assertRaisesRegex(ValueError, "contain only"):
                github.download(current, "unexpected.json", maximum=65536)


class RolloutOrderTest(unittest.TestCase):
    def run_rollout(self, components, *, fail_on=None, already_ready=False):
        operations = []
        test = self

        class Host:
            def __init__(self, *args):
                self.remote = "/tmp/speaky-release.mockstage"

            def prepare(self):
                pass

            def close(self):
                pass

            def copy(self, *_args):
                pass

            def invoke(self, method, environment, component, *_args):
                test.assertEqual(environment, "dev")
                operations.append((component, method))
                if (component, method) == fail_on:
                    raise rollout.RolloutError("test readiness failure")
                if method == "activate":
                    return {"outcome": "already_ready" if already_ready else "ready"}
                return {"outcome": method}

        environment = {"SPEAKY_RELEASE_ENABLED": "true",
                       "SPEAKY_RELEASE_RUNTIME_FAMILY": "foundation",
                       "SPEAKY_RELEASE_KNOWN_HOSTS": "test pins"}
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(rollout, "Host", Host), patch.object(rollout, "miniapp_edge"), \
                patch.dict(rollout.os.environ, environment, clear=True):
            directory = Path(directory)
            release.write_json(directory / "release.json", sample())
            result = rollout.apply(argparse.Namespace(environment="dev", components=components,
                                                     directory=directory))
            return (result, operations, release.load_json(directory / "rollout.json"),
                    (directory / "receipt.json").is_file())

    def test_stage_all_images_before_activate_and_publish_receipt(self):
        success, operations, report, receipt = self.run_rollout("all")
        self.assertTrue(success and receipt)
        self.assertEqual(operations[:6], [(name, step) for step in ("stage", "activate")
                                           for name in COMPONENTS])
        self.assertTrue(all(row["status"] == "ready" for row in report["components"].values()))

    def test_readiness_failure_on_partial_rollout_restores_the_selected_component(self):
        success, operations, report, receipt = self.run_rollout("ai", fail_on=("ai", "links"))
        self.assertFalse(success or receipt)
        self.assertEqual(operations[-2:], [("ai", "restore"), ("ai", "status")])
        self.assertEqual(report["components"]["ai"]["recovery"], "restore")

    def test_probe_failure_on_a_repeat_does_not_restore_the_previous_release(self):
        success, operations, report, receipt = self.run_rollout(
            "ai", fail_on=("ai", "links"), already_ready=True)
        self.assertFalse(success or receipt)
        self.assertNotIn(("ai", "restore"), operations)
        self.assertEqual(report["components"]["ai"]["recovery"], "unchanged")

    def test_uncertain_ssh_activation_does_not_trigger_a_second_mutation(self):
        success, operations, report, receipt = self.run_rollout("ai", fail_on=("ai", "activate"))
        self.assertFalse(success or receipt)
        self.assertNotIn(("ai", "restore"), operations)
        self.assertEqual(report["components"]["ai"]["recovery"], "activation_outcome_uncertain")

    def test_failure_after_ktor_has_no_automatic_restore(self):
        success, operations, report, receipt = self.run_rollout("all", fail_on=("ai", "activate"))
        self.assertFalse(success or receipt)
        self.assertNotIn(("ktor", "restore"), operations)
        self.assertNotIn(("miniapp", "activate"), operations)
        self.assertEqual(report["components"]["ktor"]["recovery"], "operator_schema_check_required")


if __name__ == "__main__":
    unittest.main()
