"""Host installer contract tests, with an opt-in isolated Docker exercise."""

import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("speaky_host_install", Path(__file__).with_name("host-install.py"))
host = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = host
spec.loader.exec_module(host)


class OverrideTests(unittest.TestCase):
    def test_allowlist_requires_complete_mock_billing(self):
        header = b"# SPEAKY_EXPIRES_UTC=1800000000\n"
        with self.assertRaisesRegex(host.InstallError, "invalid DEV billing override"):
            host.override_values(header + b"SPEAKY_BILLING_PROVIDER=mock\n", "ktor")
        with self.assertRaisesRegex(host.InstallError, "invalid DEV overrides"):
            host.override_values(header + b"AI_INTERNAL_TOKEN=something\n", "ai")
        self.assertEqual(host.override_values(header + b"AGENT_TEST_ENABLED=true\n", "ai"),
                         (1800000000, {"AGENT_TEST_ENABLED": "true"}))

    def test_ktor_readiness_failure_keeps_transaction_recoverable_without_restoring(self):
        release = host.Release("2", "1", "2" * 40, "foundation", "sha256:" + "2" * 64, "linux/amd64", "2" * 64)
        old = {"Id": "old", "Image": "sha256:" + "1" * 64,
               "Config": {"Labels": {host.FAMILY: "foundation"}, "Env": []}, "State": {"Running": True}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = host.Installer("dev", "ktor", root, root)
            host.write_json(installer.staged, host.asdict(release))
            with patch.object(host, "inspect", return_value=old), patch.object(installer, "image"), \
                    patch.object(installer, "runtime_options"), patch.object(installer, "report"), \
                    patch.object(installer, "replace", side_effect=host.InstallError("not ready")), \
                    patch.object(installer, "restore_transaction") as restore:
                with self.assertRaisesRegex(host.InstallError, "not ready"):
                    installer.activate(release)
                restore.assert_not_called()
            transaction = host.read_json(installer.journal)
            self.assertEqual(transaction["phase"], "activating")
            self.assertEqual(transaction["previous_id"], "old")

    def test_expired_config_prevents_restoring_retained_flagged_container(self):
        old = {"Id": "old", "Image": "sha256:" + "1" * 64,
               "Config": {"Env": ["AGENT_TEST_ENABLED=true"]}, "State": {"Running": True}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = host.Installer("dev", "ai", root, root, {"ai": host.HostConfig("fixture", root)})
            installer.base_env.write_text("AGENT_TEST_ENABLED=false\n")
            installer.override.write_text("# SPEAKY_EXPIRES_UTC=1\nAGENT_TEST_ENABLED=true\n")
            transaction = {"previous_id": "old", "previous_running": True}
            with patch.object(host, "inspect", return_value=old), patch.object(host, "docker") as docker:
                with self.assertRaisesRegex(host.InstallError, "expired DEV test config"):
                    installer.restore_transaction(transaction, installer.journal)
                docker.assert_not_called()
            self.assertFalse(installer.journal.exists())

    def test_expiry_reconciles_running_fixture_even_when_override_file_is_missing(self):
        release = host.Release("2", "1", "2" * 40, "foundation", "sha256:" + "2" * 64, "linux/amd64", "2" * 64)
        current = {"Id": "current", "Image": release.image_id,
                   "Config": {"Labels": release.labels(), "Env": ["AGENT_TEST_ENABLED=true"]},
                   "State": {"Running": True}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = host.Installer("dev", "ai", root, root, {"ai": host.HostConfig("fixture", root)})
            installer.base_env.write_text("AGENT_TEST_ENABLED=false\n")
            host.write_json(installer.journal, {"phase": "ready", "release": host.asdict(release)})
            with patch.object(host, "inspect", return_value=current), patch.object(installer, "continue_config") as resume:
                installer.restart_config(None)
            resume.assert_called_once()
            transaction = host.read_json(installer.override_journal)
            self.assertEqual(transaction["override"], "")
            self.assertEqual(transaction["previous_id"], "current")

    def test_ai_port_contract_never_expands_existing_bindings(self):
        info = {"HostConfig": {"PortBindings": {"8090/tcp": [
            {"HostIp": "127.0.0.1", "HostPort": "8010"}, {"HostIp": "::1", "HostPort": "8010"},
        ]}}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            installer = host.Installer("dev", "ai", root, root)
            with patch.object(host, "inspect", return_value=info):
                self.assertEqual(installer.ai_bindings({"AI_SERVICE_PORT": "8010"}),
                                 ["-p", "127.0.0.1:8010:8090", "-p", "[::1]:8010:8090"])
                with self.assertRaisesRegex(host.InstallError, "existing firewall-protected binding"):
                    installer.ai_bindings({"AI_SERVICE_PORT": "8090"})


@unittest.skipUnless(os.environ.get("SPEAKY_HOST_DOCKER_TEST_IMAGE"),
                     "requires an existing nginx Alpine image set in SPEAKY_HOST_DOCKER_TEST_IMAGE")
class DockerInstallerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.name = "speaky-release-test-" + secrets.token_hex(6)
        (self.root / ".dockerignore").write_text("*\n!Dockerfile\n!fixture.conf\n!fixture-version.json\n")
        self.installer = host.Installer("dev", "miniapp", self.root, self.root, {
            "miniapp": host.HostConfig(self.name, self.root, "none"),
        })
        self.tags = []
        self.network = None
        self.addCleanup(self.remove_images)

    def remove_containers(self):
        for identity in host.docker("ps", "-aq", "--filter", f"name={self.name}").splitlines():
            host.docker("rm", "-f", "-v", identity)

    def remove_images(self):
        self.remove_containers()
        for tag in self.tags:
            if host.inspect(tag, "image"):
                host.docker("image", "rm", tag)
        if self.network:
            host.docker("network", "rm", self.network)

    def build(self, digit, run, component="miniapp", port=8080):
        source_sha = digit * 40
        base = os.environ["SPEAKY_HOST_DOCKER_TEST_IMAGE"]
        tag = f"{self.name}-{component}:{run}"
        self.tags.append(tag)
        (self.root / "Dockerfile").write_text(
            f"FROM {base}\n"
            f'LABEL {host.REVISION}="{source_sha}" {host.FAMILY}="foundation" {host.COMPONENT}="{component}"\n'
            "COPY fixture.conf /etc/nginx/conf.d/default.conf\n"
            "COPY fixture-version.json /usr/share/nginx/html/version.json\n"
        )
        listener = f"127.0.0.1:{port}" if component == "ktor" else str(port)
        (self.root / "fixture.conf").write_text(
            f'server {{ listen {listener}; location /miniapp {{ return 200 "fixture"; }} '
            'location /health { return 200 "ok"; } '
            'location = /version.json { root /usr/share/nginx/html; } }\n'
        )
        (self.root / "fixture-version.json").write_text(json.dumps({"revision": source_sha}))
        host.docker("build", "-t", tag, str(self.root), timeout=120)
        archive = self.root / f"image-{component}-{run}.tar.gz"
        with archive.open("wb") as output:
            with subprocess.Popen(["docker", "image", "save", tag], stdout=subprocess.PIPE) as save:
                subprocess.run(["gzip"], stdin=save.stdout, stdout=output, check=True)
                self.assertEqual(save.wait(), 0)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        return host.Release(str(run), "1", source_sha, "foundation", host.inspect(tag, "image")["Id"],
                            self.installer.host_platform(), digest), archive

    def test_preflight_activate_resume_and_restore_keep_previous_config(self):
        first, _ = self.build("1", 1)
        second, archive = self.build("2", 2)
        old = host.docker("run", "-d", "--name", self.name, "--network", "none",
                          "-e", "FIXTURE_OLD_CONFIG=original", first.image_id)
        self.installer.wait_ready(old, first.sha)
        old_ai, ai, ai_release, ai_archive = self.prepare_ai()
        ktor = self.prepare_ktor()
        with self.assertRaisesRegex(host.InstallError, "was not staged"):
            self.installer.activate(second)
        self.assertEqual(host.inspect(self.name)["Id"], old)
        wrong = host.Release(**{**host.asdict(second), "archive_sha": "0" * 64})
        with self.assertRaisesRegex(host.InstallError, "archive checksum mismatch"):
            self.installer.stage(wrong, archive)
        self.assertEqual(host.inspect(self.name)["Id"], old)
        host.docker("image", "rm", f"{self.name}-miniapp:2")
        self.assertIsNone(host.inspect(second.image_id, "image"))
        self.installer.stage(second, archive)
        ai.stage(ai_release, ai_archive)
        self.assertEqual(host.inspect(self.name)["Id"], old)
        self.assertEqual(host.inspect(ai.name)["Id"], old_ai)
        if ktor:
            old_ktor, backend, backend_release, backend_archive = ktor
            backend.stage(backend_release, backend_archive)
            self.assertEqual(host.inspect(backend.name)["Id"], old_ktor)
            backend.activate(backend_release)
            backend.verify_current(backend_release, host.inspect(backend.name))
            self.assertEqual(host.inspect(ai.name)["Id"], old_ai)
        ai.activate(ai_release)
        deployed_ai = host.inspect(ai.name)
        ai.verify_current(ai_release, deployed_ai)
        self.assertEqual(deployed_ai["HostConfig"]["PortBindings"],
                         host.inspect(old_ai)["HostConfig"]["PortBindings"])
        self.assertEqual(host.inspect(self.name)["Id"], old)
        # Stop mid-activation, after preserving the old container and before the name is acquired.
        transaction = self.installer.new_transaction(second, host.inspect(old))
        host.write_json(self.installer.journal, transaction)
        host.docker("stop", old)
        host.docker("rename", old, self.installer.transaction_name(transaction, "previous"))
        self.assertIsNone(host.inspect(self.name))
        self.installer.stage(second, archive)
        self.installer.activate(second)
        deployed = host.inspect(self.name)
        self.installer.verify_current(second, deployed)
        self.assertFalse(host.inspect(old)["State"]["Running"])
        self.installer.activate(second)
        self.assertEqual(host.inspect(self.name)["Id"], deployed["Id"])
        self.installer.restore(second)
        restored = host.inspect(self.name)
        self.assertEqual(restored["Id"], old)
        self.assertTrue(restored["State"]["Running"])
        self.assertEqual(host.container_env(restored)["FIXTURE_OLD_CONFIG"], "original")
        self.installer.restore(second)
        self.assertEqual(host.inspect(self.name)["Id"], old)
        self.assertEqual(host.inspect(ai.name)["Image"], ai_release.image_id)
        with patch("sys.stdout", new_callable=io.StringIO) as output:
            self.installer.status(second)
        observed = json.loads(output.getvalue())
        self.assertEqual(observed["outcome"], "target_mismatch_or_not_ready")
        self.assertEqual(observed["image_id"], first.image_id)
        self.assertEqual(observed["expected_image_id"], second.image_id)
        self.exercise_test_config(ai, ai_release)

    def exercise_test_config(self, ai, release):
        input_path = self.root / "fixture-input.env"
        input_path.write_text(f"# SPEAKY_EXPIRES_UTC={int(time.time()) + 600}\nAGENT_TEST_ENABLED=true\n")
        ai.restart_config(str(input_path))
        active = host.inspect(ai.name)
        self.assertEqual(active["Image"], release.image_id)
        self.assertEqual(host.container_env(active)["AGENT_TEST_ENABLED"], "true")
        ai.override.unlink()
        ai.restart_config(None)
        cleared = host.inspect(ai.name)
        self.assertEqual(cleared["Image"], release.image_id)
        self.assertFalse(host.container_env(cleared).get("AGENT_TEST_ENABLED") == "true")

    def prepare_ai(self):
        first, _ = self.build("1", 1, "ai", 8090)
        second, archive = self.build("2", 2, "ai", 8090)
        self.network = self.name + "-network"
        host.docker("network", "create", self.network)
        directory = self.root / "ai"
        directory.mkdir()
        (directory / "logs").mkdir()
        with socket.socket() as port_socket:
            port_socket.bind(("127.0.0.1", 0))
            port = port_socket.getsockname()[1]
        (directory / ".env").write_text(
            f"AI_SERVICE_PORT={port}\nAI_INTERNAL_TOKEN=fixture\nFOUNDATION_BACKEND_URL=http://fixture.invalid\n"
        )
        ai = host.Installer("dev", "ai", self.root, self.root, {
            "ai": host.HostConfig(self.name + "-ai", directory, self.network),
        })
        started = subprocess.run(["docker", "run", "-d", "--name", ai.name, "--network", self.network,
                                  "-p", f"127.0.0.1:{port}:8090", first.image_id], capture_output=True, text=True)
        self.assertEqual(started.returncode, 0, started.stderr)
        old = started.stdout.strip()
        ai.wait_ready(old, first.sha)
        return old, ai, second, archive

    def prepare_ktor(self):
        postgres = os.environ.get("SPEAKY_HOST_DOCKER_POSTGRES_IMAGE")
        if not postgres:
            return None
        with socket.socket() as app_socket, socket.socket() as postgres_socket:
            app_socket.bind(("127.0.0.1", 0))
            postgres_socket.bind(("127.0.0.1", 0))
            port, pg_port = app_socket.getsockname()[1], postgres_socket.getsockname()[1]
        first, _ = self.build("1", 1, "ktor", port)
        second, archive = self.build("2", 2, "ktor", port)
        host.docker("run", "-d", "--name", self.name + "-postgres", "-e", "POSTGRES_PASSWORD=fixture-only",
                    "-p", f"127.0.0.1:{pg_port}:5432", postgres)
        dsn = f"postgresql://127.0.0.1:{pg_port}/postgres"
        deadline = time.monotonic() + 30
        while True:
            try:
                host.postgresql_ready(dsn)
                break
            except (OSError, host.InstallError):
                if time.monotonic() > deadline:
                    raise
                time.sleep(1)
        directory = self.root / "ktor"
        directory.mkdir()
        for filename in ("logs", "analytics", "audit-audio"):
            (directory / filename).mkdir()
        for filename in ("tls.crt", "tls.key"):
            (directory / filename).touch()
        backend = host.Installer("dev", "ktor", self.root, self.root, {
            "ktor": host.HostConfig(self.name + "-ktor", directory),
        })
        backend.base_env.write_text(f"SERVER_PORT={port}\nDATABASE_URL={dsn}\nAI_INTERNAL_TOKEN=fixture\n")
        if sys.platform == "darwin":
            # Docker Desktop's Linux host loopback differs from macOS loopback.
            def ready_inside_vm(info, revision=None):
                if not info or not info["State"]["Running"]:
                    return False
                try:
                    host.docker("exec", info["Id"], "wget", "-T", "5", "-q", "-O", "/dev/null",
                                f"http://127.0.0.1:{port}/health")
                    return True
                except host.InstallError:
                    return False
            adapter = patch.object(backend, "ready", side_effect=ready_inside_vm)
            adapter.start()
            self.addCleanup(adapter.stop)
        old = host.docker("run", "-d", "--name", backend.name, "--network", "host",
                          "--env-file", str(backend.base_env), first.image_id)
        backend.wait_ready(old, first.sha)
        return old, backend, second, archive

    def test_failed_activation_restores_previous_image(self):
        first, _ = self.build("1", 1)
        second, archive = self.build("2", 2)
        old = host.docker("run", "-d", "--name", self.name, "--network", "none", first.image_id)
        self.installer.stage(second, archive)
        real_ready = self.installer.ready

        def reject_new(info, revision=None):
            return False if revision == second.sha else real_ready(info, revision)

        with patch.object(self.installer, "ready", side_effect=reject_new), patch.object(host, "READY_TIMEOUT", 2):
            with self.assertRaisesRegex(host.InstallError, "application readiness failed"):
                self.installer.activate(second)
        self.assertEqual(host.inspect(self.name)["Id"], old)
        self.assertTrue(host.inspect(old)["State"]["Running"])


if __name__ == "__main__":
    unittest.main()
