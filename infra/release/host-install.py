#!/usr/bin/env python3
"""Install verified Speaky images with the host's existing runtime configuration."""

from __future__ import annotations

import argparse
from dataclasses import asdict, dataclass
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


REVISION = "org.opencontainers.image.revision"
FAMILY = "io.speaky.runtime-family"
COMPONENT = "io.speaky.component"
RELEASE_RUN = "io.speaky.release-run"
BUILD_ATTEMPT = "io.speaky.build-attempt"
TRANSACTION = "io.speaky.transaction"
STAGE_PROBE = "io.speaky.stage-probe"
READY_TIMEOUT = 90
MOCK_BILLING = {"SPEAKY_BILLING_PROVIDER": "mock", "SPEAKY_BILLING_AMOUNT": "100",
                "SPEAKY_BILLING_CURRENCY": "TEST", "SPEAKY_BILLING_PERIOD_DAYS": "1"}


class InstallError(Exception):
    pass


def require(condition, message):
    if not condition:
        raise InstallError(message)


@dataclass(frozen=True)
class Release:
    run_id: str
    attempt: str
    sha: str
    family: str
    image_id: str
    platform: str
    archive_sha: str

    def validate(self):
        require(re.fullmatch(r"[1-9][0-9]*", self.run_id), "invalid release run ID")
        require(re.fullmatch(r"[1-9][0-9]*", self.attempt), "invalid build attempt")
        require(re.fullmatch(r"[0-9a-f]{40}", self.sha), "invalid source SHA")
        require(self.family in ("foundation", "legacy"), "invalid runtime family")
        require(re.fullmatch(r"sha256:[0-9a-f]{64}", self.image_id), "invalid image ID")
        require(re.fullmatch(r"linux/(amd64|arm64)", self.platform), "invalid image platform")
        require(re.fullmatch(r"[0-9a-f]{64}", self.archive_sha), "invalid archive SHA")

    def labels(self):
        return {REVISION: self.sha, FAMILY: self.family, RELEASE_RUN: self.run_id, BUILD_ATTEMPT: self.attempt}


@dataclass(frozen=True)
class HostConfig:
    name: str
    directory: Path
    network: str = "speaking-coach"
    local_tag: str | None = None


CONFIG = {
    "ktor": HostConfig("speaking-coach", Path("/opt/speaking-coach"), local_tag="speaking-coach:local"),
    "ai": HostConfig("ai-service", Path("/opt/ai-service"), local_tag="ai-service:local"),
    "miniapp": HostConfig("mini-app", Path("/opt/mini-app")),
}


def docker(*args, timeout=30, **options):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=timeout, **options)
    if result.returncode:
        raise InstallError(f"Docker {args[0]} failed")
    return result.stdout.strip()


def inspect(name, kind="container"):
    result = subprocess.run(["docker", kind, "inspect", name], capture_output=True, text=True, timeout=15)
    if not result.returncode:
        return json.loads(result.stdout)[0]
    if "No such" in result.stderr:
        return None
    raise InstallError(f"Docker {kind} inspect failed")


def env_values(lines):
    values = {}
    for line in lines:
        key, separator, value = line.lstrip().partition("=")
        if not key or key.startswith("#"):
            continue
        require(separator and re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key), "invalid runtime env file")
        values[key] = value
    return values


def container_env(info):
    return env_values(info["Config"]["Env"] or [])


def atomic_write(path, data):
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as temporary:
        name = temporary.name
        try:
            temporary.write(data)
            temporary.flush()
            os.fsync(temporary.fileno())
            os.replace(name, path)
            directory = os.open(path.parent, os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            Path(name).unlink(missing_ok=True)


def read_json(path):
    return json.loads(path.read_text()) if path.exists() else None


def write_json(path, value):
    atomic_write(path, json.dumps(value, sort_keys=True).encode())


def status_code(url, *, context=None, token=None, payload=None):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None

    headers = {}
    if token:
        headers["X-Internal-Token"] = token
    if payload is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=payload, headers=headers)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                         urllib.request.HTTPSHandler(context=context), NoRedirect)
    try:
        with opener.open(request, timeout=5) as response:
            return response.status
    except urllib.error.HTTPError as error:
        error.close()
        return error.code
    except (OSError, urllib.error.URLError):
        return 0


def runtime_port(raw):
    require(re.fullmatch(r"[0-9]{1,5}", raw) and 1 <= int(raw) <= 65535, "invalid runtime port")
    return int(raw)


def postgresql_ready(url):
    target = urllib.parse.urlsplit(url.removeprefix("jdbc:"))
    require(target.scheme == "postgresql" and target.hostname, "invalid PostgreSQL endpoint")
    with socket.create_connection((target.hostname, target.port or 5432), timeout=5) as connection:
        connection.settimeout(5)
        # PostgreSQL SSLRequest is read-only and confirms the configured port speaks PostgreSQL.
        connection.sendall(b"\x00\x00\x00\x08\x04\xd2\x16\x2f")
        require(connection.recv(1) in (b"S", b"N"), "PostgreSQL did not accept its readiness probe")
    if target.hostname in ("localhost", "127.0.0.1", "::1") and inspect("postgres") is not None:
        docker("exec", "postgres", "pg_isready", "-q", "-h", "127.0.0.1")


def override_values(data, component):
    lines = data.decode().splitlines()
    deadline = re.fullmatch(r"# SPEAKY_EXPIRES_UTC=([1-9][0-9]{0,11})", lines[0] if lines else "")
    require(deadline, "invalid DEV override expiration")
    accepted = {"AGENT_TEST_ENABLED": "true"}
    values = env_values(lines[1:])
    if component == "ktor" and set(values) & set(MOCK_BILLING):
        require(all(values.get(key) == value for key, value in MOCK_BILLING.items()), "invalid DEV billing override")
        accepted.update(MOCK_BILLING)
    require(values and all(accepted.get(key) == value for key, value in values.items()), "invalid DEV overrides")
    return int(deadline[1]), values


class Installer:
    def __init__(self, environment, component, state_root=Path("/var/lib/speaky-release"),
                 lock_root=Path("/var/lock"), config=CONFIG):
        self.environment = environment
        self.component = component
        self.config = config[component]
        self.name = self.config.name
        self.base_env = self.config.directory / ".env"
        self.override = self.config.directory / ".release-dev-overrides.env"
        self.root = state_root / environment
        self.root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.lock = lock_root / f"speaky-release-{environment}.lock"
        self.journal = self.root / f"{component}.json"
        self.staged = self.root / f"{component}-staged.json"
        self.override_journal = self.root / f"{component}-config.json"

    def image(self, release):
        info = inspect(release.image_id, "image")
        require(info is not None, "expected image ID is missing")
        require(info["Id"] == release.image_id, "image ID mismatch")
        require(f'{info["Os"]}/{info["Architecture"]}' == release.platform, "image platform mismatch")
        labels = info["Config"].get("Labels") or {}
        require(all(labels.get(key) == value for key, value in
                    {REVISION: release.sha, FAMILY: release.family, COMPONENT: self.component}.items()),
                "image release labels mismatch")

    def host_platform(self):
        system, architecture = docker("info", "--format", "{{.OSType}} {{.Architecture}}").split()
        architecture = {"x86_64": "amd64", "aarch64": "arm64"}.get(architecture, architecture)
        return f"{system}/{architecture}"

    def existing_family(self, info):
        labels = info["Config"]["Labels"] or {}
        if labels.get(FAMILY) in ("legacy", "foundation"):
            return labels[FAMILY]
        if self.component == "miniapp":
            return "foundation"
        if self.component == "ai":
            command = info["Config"]["Cmd"] or []
            if "app.speaky_main:create_app" in command:
                return "foundation"
            if "app.main:create_app" in command or "app.legacy.main:create_app" in command:
                return "legacy"
        if self.component == "ktor" and info["State"]["Running"]:
            root, context = self.ktor_endpoint(container_env(info))
            status = status_code(root + "/internal/foundation/v1/snapshot", context=context)
            return {401: "foundation", 404: "legacy"}.get(status)
        return None

    def ktor_endpoint(self, env):
        port = runtime_port(env.get("SERVER_PORT", "").strip())
        tls = env.get("TELEGRAM_WEBHOOK_URL", "").strip()
        if tls:
            context = ssl.create_default_context(cafile=self.config.directory / "tls.crt")
            context.check_hostname = False  # The deployed certificate pins the loopback service.
            return f"https://127.0.0.1:{port}", context
        return f"http://127.0.0.1:{port}", None

    def active_overrides(self):
        if self.environment != "dev" or not self.override.exists():
            return {}
        expires, values = override_values(self.override.read_bytes(), self.component)
        return values if expires > time.time() else {}

    def test_config(self, env):
        agent_test = env.get("AGENT_TEST_ENABLED") == "true"
        mock = self.component == "ktor" and env.get("SPEAKY_BILLING_PROVIDER") == "mock"
        billing = tuple(env.get(key, "") for key in MOCK_BILLING) if mock else ()
        return agent_test, billing

    def expected_test_config(self):
        base = env_values(self.base_env.read_text().splitlines())
        return self.test_config({**base, **self.active_overrides()})

    def ai_bindings(self, env):
        configured = runtime_port(env.get("AI_SERVICE_PORT", "8090").partition("#")[0].strip())
        current = inspect(self.name)
        if current is None:
            for journal in (self.override_journal, self.journal):
                transaction = read_json(journal)
                if transaction and transaction["phase"] in ("activating", "restoring"):
                    current = inspect(transaction["previous_id"])
                    break
        require(current, "existing AI port binding is unknown")
        bindings = current["HostConfig"]["PortBindings"] or {}
        require(set(bindings) == {"8090/tcp"}, "existing AI port binding does not match its runtime contract")
        options = []
        for binding in bindings["8090/tcp"]:
            require(runtime_port(binding["HostPort"]) == configured,
                    "configured AI port differs from the existing firewall-protected binding")
            address = binding["HostIp"]
            if address:
                ip = ipaddress.ip_address(address)
                address = f"[{address}]:" if ip.version == 6 else f"{address}:"
            options.extend(("-p", f"{address}{configured}:8090"))
        require(options, "existing AI port binding is missing")
        return options

    def runtime_options(self, family):
        directory = self.config.directory
        options = ["--restart", "unless-stopped", "--log-driver", "local",
                   "--log-opt", "max-size=10m", "--log-opt", "max-file=3"]
        if self.component != "miniapp":
            require(self.base_env.is_file(), "runtime env file is missing")
            env = env_values(self.base_env.read_text().splitlines())
            require(env, "runtime env file is empty")
            if family == "foundation":
                require(env.get("AI_INTERNAL_TOKEN", "").strip(), "Foundation service token missing")
            options.extend(("--env-file", str(self.base_env)))
            if self.active_overrides():
                options.extend(("--env-file", str(self.override)))
        storage = False
        if self.component == "ktor":
            runtime_port(env.get("SERVER_PORT", "").strip())
            url = env.get("DATABASE_URL", "").strip()
            require(family != "foundation" or url, "Foundation PostgreSQL configuration missing")
            if url:
                postgresql_ready(url)
            options.extend(("--network", "host"))
            for filename, read_only, is_file in (("tls.crt", True, True), ("tls.key", True, True),
                                                  ("logs", False, False), ("audit-audio", False, False),
                                                  ("analytics", True, False)):
                path = directory / filename
                require(path.is_file() if is_file else path.is_dir(), f"runtime mount missing: {filename}")
                options.extend(("-v", f"{path}:/opt/speaking-coach/{filename}" + (":ro" if read_only else "")))
            if env.get("TELEGRAM_WEBHOOK_URL", "").strip():
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.load_cert_chain(directory / "tls.crt", directory / "tls.key")
        else:
            docker("network", "inspect", self.config.network)
            options.extend(("--network", self.config.network))
        if self.component == "ai":
            require(family != "foundation" or env.get("FOUNDATION_BACKEND_URL", "").strip(),
                    "Foundation backend configuration missing")
            options.extend(self.ai_bindings(env))
            logs = directory / "logs"
            require(logs.is_dir(), "runtime mount missing: logs")
            options.extend(("-e", "LOG_FILE=/var/log/ai-service/app.log", "-v", f"{logs}:/var/log/ai-service"))
            if env.get("TRIAL_S3_ENDPOINT") == "http://speaky-storage:8333":
                docker("network", "inspect", "speaky-storage")
                storage = True
            ca = directory / "foundation-backend-ca.pem"
            if ca.is_file() and ca.stat().st_size:
                ssl.create_default_context(cafile=ca)
                options.extend(("-v", f"{ca}:/app/foundation-backend-ca.pem:ro",
                                "-e", "FOUNDATION_BACKEND_CA_FILE=/app/foundation-backend-ca.pem"))
        return options, storage

    def ready(self, info, revision=None):
        if not info or not info["State"]["Running"] or info["State"]["OOMKilled"]:
            return False
        identity = info["Id"]
        if self.component == "ktor":
            root, context = self.ktor_endpoint(container_env(info))
            return status_code(root + "/health", context=context) == 200
        if self.component == "ai":
            bindings = (info["NetworkSettings"]["Ports"] or {}).get("8090/tcp")
            if not bindings:
                return False
            for binding in bindings:
                port = runtime_port(binding["HostPort"])
                address = binding["HostIp"]
                address = {"": "127.0.0.1", "0.0.0.0": "127.0.0.1", "::": "::1"}.get(address, address)
                address = f"[{address}]" if ":" in address else address
                if status_code(f"http://{address}:{port}/health") == 200:
                    return True
            return False
        try:
            for path in ("/health", "/miniapp", "/miniapp/assets/app.js"):
                docker("exec", identity, "wget", "-T", "5", "-q", "-O", "/dev/null", f"http://127.0.0.1:8080{path}")
            if revision is not None:
                version = docker("exec", identity, "wget", "-T", "5", "-q", "-O", "-",
                                 "http://127.0.0.1:8080/version.json")
                return json.loads(version).get("revision") == revision
        except (InstallError, ValueError):
            return False
        return True

    def wait_ready(self, identity, revision=None):
        end = time.monotonic() + READY_TIMEOUT
        while True:
            info = inspect(identity)
            if self.ready(info, revision):
                return
            if not info or time.monotonic() >= end:
                raise InstallError("application readiness failed")
            time.sleep(2)

    def report(self, outcome, transaction=None, *, release=None, current=None, reason=None):
        info = current if current is not None else inspect(self.name)
        previous = (transaction or {}).get("previous_image")
        output = {"outcome": outcome, "component": self.component, "environment": self.environment,
                  "image_id": info["Image"] if info else None, "previous_image_id": previous,
                  "running": bool(info and info["State"]["Running"])}
        if info:
            labels = info["Config"]["Labels"] or {}
            for field, key, pattern in (("source_sha", REVISION, r"[0-9a-f]{40}"),
                                        ("release_run_id", RELEASE_RUN, r"[1-9][0-9]*"),
                                        ("build_attempt", BUILD_ATTEMPT, r"[1-9][0-9]*"),
                                        ("runtime_family", FAMILY, r"foundation|legacy")):
                value = labels.get(key, "")
                if field == "source_sha" and not value:
                    value = next((row.removeprefix("SPEAKY_RELEASE=") for row in info["Config"]["Env"] or []
                                  if row.startswith("SPEAKY_RELEASE=")), "")
                output[field] = value if re.fullmatch(pattern, value) else None
        if release:
            output["expected_image_id"] = release.image_id
        if transaction:
            output["journal_phase"] = transaction["phase"]
        if reason:
            output["reason"] = reason
        print(json.dumps(output, sort_keys=True), flush=True)

    def stage(self, release, archive):
        require(archive and archive.is_absolute() and archive.is_file(), "absolute image archive path required")
        require(self.host_platform() == release.platform, "host platform mismatch")
        pending = read_json(self.journal)
        if pending and pending["phase"] in ("activating", "restoring"):
            require(pending["release"] == asdict(release), "unfinished release requires recovery on this host")
        else:
            self.baseline(release.family)
        self.runtime_options(release.family)
        with archive.open("rb") as stream:
            digest = hashlib.sha256()
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
            require(digest.hexdigest() == release.archive_sha, "archive checksum mismatch")
            stream.seek(0)
            docker("image", "load", timeout=600, stdin=stream)
        self.image(release)
        if self.component == "miniapp":
            name = f"{self.name}-release-probe"
            orphan = inspect(name)
            if orphan is not None:
                require((orphan["Config"]["Labels"] or {}).get(STAGE_PROBE) == self.environment,
                        "Mini App probe name already in use")
                docker("rm", "-f", orphan["Id"])
            identity = docker("run", "--detach", "--rm", "--name", name, "--network", "none",
                              "--label", f"{STAGE_PROBE}={self.environment}", release.image_id)
            try:
                self.wait_ready(identity, release.sha)
            finally:
                if inspect(identity):
                    docker("rm", "-f", identity)
        write_json(self.staged, asdict(release))
        self.report("staged")

    def new_transaction(self, release, previous, retired=None):
        return {"phase": "activating", "release": asdict(release), "nonce": secrets.token_hex(8),
                "previous_id": previous["Id"], "previous_image": previous["Image"],
                "previous_running": previous["State"]["Running"], "retired_id": retired}

    def transaction_name(self, transaction, role):
        return f"{self.name}-release-{role}-{transaction['nonce']}"

    def candidate(self, transaction, release, runtime):
        options, storage = runtime
        name = self.transaction_name(transaction, "candidate")
        current = inspect(self.name)
        if current and (current["Config"]["Labels"] or {}).get(TRANSACTION) == transaction["nonce"]:
            info = current
        else:
            info = inspect(name)
            if info is None:
                opts = ["--label", f"{TRANSACTION}={transaction['nonce']}"]
                for key, value in release.labels().items():
                    opts.extend(("--label", f"{key}={value}"))
                if self.component != "miniapp":
                    opts.extend(("-e", f"SPEAKY_ENV={self.environment}", "-e", f"SPEAKY_RELEASE={release.sha}"))
                identity = docker("create", "--name", name, *opts, *options, release.image_id)
                info = inspect(identity)
        require(info and info["Image"] == release.image_id and
                (info["Config"]["Labels"] or {}).get(TRANSACTION) == transaction["nonce"], "candidate identity mismatch")
        if storage and "speaky-storage" not in info["NetworkSettings"]["Networks"]:
            docker("network", "connect", "speaky-storage", info["Id"])
        return info

    def replace(self, transaction, release, options):
        candidate = self.candidate(transaction, release, options)
        identity = candidate["Id"]
        current = inspect(self.name)
        require(current is None or current["Id"] in (identity, transaction["previous_id"]),
                "active container changed during installation")
        if current and current["Id"] == transaction["previous_id"]:
            if current["State"]["Running"]:
                docker("stop", "--time", "20", current["Id"])
            docker("rename", current["Id"], self.transaction_name(transaction, "previous"))
        if not current or current["Id"] != identity:
            docker("rename", identity, self.name)
        if not inspect(identity)["State"]["Running"]:
            docker("start", identity)
        self.wait_ready(identity, release.sha)

    def remove_stopped(self, identity):
        if identity:
            info = inspect(identity)
            if info and not info["State"]["Running"] and info["Name"] != "/" + self.name:
                docker("rm", identity)

    def publish_local_tag(self, image_id):
        if self.config.local_tag:
            docker("image", "tag", image_id, self.config.local_tag)

    def restore_transaction(self, transaction, journal, *, restart_previous=True):
        previous = inspect(transaction["previous_id"])
        require(previous is not None, "previous container missing")
        if restart_previous and transaction["previous_running"] and self.environment == "dev" and self.component != "miniapp":
            require(self.test_config(container_env(previous)) == self.expected_test_config(),
                    "previous container has incompatible or expired DEV test config")
        transaction["phase"] = "restoring"
        write_json(journal, transaction)
        current = inspect(self.name)
        if current and (current["Config"]["Labels"] or {}).get(TRANSACTION) == transaction["nonce"]:
            if current["State"]["Running"]:
                docker("stop", "--time", "20", current["Id"])
            docker("rm", current["Id"])
            current = None
        require(current is None or current["Id"] == transaction["previous_id"], "active container changed since release")
        candidate = inspect(self.transaction_name(transaction, "candidate"))
        if candidate:
            require((candidate["Config"]["Labels"] or {}).get(TRANSACTION) == transaction["nonce"],
                    "candidate identity mismatch during recovery")
            docker("rm", "-f", candidate["Id"])
        if current is None:
            docker("rename", previous["Id"], self.name)
        if restart_previous and transaction["previous_running"]:
            if not previous["State"]["Running"]:
                docker("start", previous["Id"])
            self.wait_ready(previous["Id"])
            self.publish_local_tag(previous["Image"])
        if not restart_previous and previous["State"]["Running"]:
            docker("stop", "--time", "20", previous["Id"])
        transaction["phase"] = "restored"
        write_json(journal, transaction)

    def activate(self, release):
        require(read_json(self.staged) == asdict(release), "release was not staged on this host")
        self.image(release)
        transaction = read_json(self.journal)
        if transaction and transaction["phase"] == "restoring":
            self.restore_transaction(transaction, self.journal)
        current = inspect(self.name)
        if transaction and transaction["phase"] == "ready" and transaction["release"] == asdict(release):
            self.verify_current(release, current)
            self.remove_stopped(transaction["retired_id"])
            self.report("already_ready", transaction)
            return
        if not transaction and current and current["Image"] == release.image_id:
            self.verify_current(release, current)
            self.report("already_ready")
            return
        if not transaction or transaction["phase"] in ("ready", "restored"):
            current = self.baseline(release.family)
            options = self.runtime_options(release.family)
            retired = None
            if transaction:
                retired = transaction["previous_id"] if transaction["phase"] == "ready" else transaction["retired_id"]
            transaction = self.new_transaction(release, current, retired)
            write_json(self.journal, transaction)
        else:
            require(transaction["release"] == asdict(release), "unfinished release requires recovery on this host")
            options = self.runtime_options(release.family)
        try:
            self.replace(transaction, release, options)
            self.verify_current(release, inspect(self.name))
        except (InstallError, OSError, ValueError, subprocess.TimeoutExpired):
            if self.component != "ktor":
                self.restore_transaction(transaction, self.journal)
                self.report("restored_after_activation_failure", transaction)
            else:
                self.report("blocked_ktor_no_schema_compatibility_proof", transaction)
            raise
        self.publish_local_tag(release.image_id)
        transaction["phase"] = "ready"
        write_json(self.journal, transaction)
        self.remove_stopped(transaction["retired_id"])
        self.report("ready", transaction)

    def verify_current(self, release, current):
        require(current and current["Image"] == release.image_id, "active image ID mismatch")
        labels = current["Config"]["Labels"] or {}
        require(all(labels.get(key) == value for key, value in release.labels().items()), "active release labels mismatch")
        require(self.ready(current, release.sha), "active application readiness failed")

    def status(self, release):
        transaction = read_json(self.journal)
        current = inspect(self.name)
        try:
            self.verify_current(release, current)
            outcome = "ready"
        except (InstallError, OSError, ValueError, subprocess.TimeoutExpired):
            outcome = "target_mismatch_or_not_ready"
        self.report(outcome, transaction, release=release, current=current)

    def restore(self, release):
        require(self.component != "ktor", "Ktor restore requires PostgreSQL schema compatibility evidence")
        transaction = read_json(self.journal)
        if not transaction or transaction["release"] != asdict(release):
            self.report("restore_no_change")
            return
        if transaction["phase"] == "restored":
            self.report("already_restored", transaction)
            return
        self.restore_transaction(transaction, self.journal)
        self.report("restored", transaction)

    def links(self, family):
        current = self.baseline(family)
        require(self.ready(current), "active runtime readiness failed")
        env = container_env(current)
        if self.component == "ktor":
            root = env.get("AI_SERVICE_BASE_URL", "http://127.0.0.1:8090").rstrip("/")
            token = env.get("AI_INTERNAL_TOKEN", "")
            require(token, "Ktor service token missing")
            require(status_code(root + "/health") == 200, "Ktor cannot reach AI health")
            if family == "foundation":
                path, body, expected = "/internal/foundation/compute", b"{}", 422
            else:
                # The legacy route only accepts POST. Middleware sends 401 before routing for a bad token.
                path, body, expected = "/internal/calls/status", None, 405
            require(status_code(root + path, token=token, payload=body) == expected, "Ktor to AI service auth failed")
        if self.component == "ai" and family == "foundation":
            # Probe from the AI network namespace with its mounted CA and actual environment.
            docker("exec", "-i", current["Id"], "python", "-", input=AI_LINK_PROBE, timeout=30)
        self.report("links_ready")

    def baseline(self, family):
        current = inspect(self.name)
        require(current and self.existing_family(current) == family, "existing runtime family mismatch or unknown")
        if self.component != "miniapp":
            environment = container_env(current).get("SPEAKY_ENV", "").strip()
            require(not environment or environment == self.environment, "existing container targets a different environment")
        return current

    def write_override(self, data):
        if data:
            atomic_write(self.override, data.encode())
        else:
            self.override.unlink(missing_ok=True)

    def continue_config(self, transaction):
        data = transaction["override"]
        if data and override_values(data.encode(), self.component)[0] <= time.time():
            self.restore_transaction(transaction, self.override_journal, restart_previous=False)
            data = ""
            release = Release(**transaction["release"])
            current = inspect(self.name)
            transaction = self.new_transaction(release, current)
            transaction["override"] = data
            transaction["old_override"] = ""
            write_json(self.override_journal, transaction)
        release = Release(**transaction["release"])
        self.write_override(data)
        try:
            self.replace(transaction, release, self.runtime_options(release.family))
        except (InstallError, OSError, ValueError, subprocess.TimeoutExpired):
            if data:
                # A failed enable can only restart the prior runtime if its own overrides are still valid.
                old = transaction["old_override"]
                safe = not old or override_values(old.encode(), self.component)[0] > time.time()
                self.write_override(old if safe else "")
                self.restore_transaction(transaction, self.override_journal, restart_previous=safe)
            else:
                # The old runtime may still accept agent requests or use mock billing.
                previous = inspect(transaction["previous_id"])
                if previous and previous["State"]["Running"]:
                    docker("stop", "--time", "20", previous["Id"])
            raise
        if data and override_values(data.encode(), self.component)[0] <= time.time():
            self.continue_config(transaction)
            return
        transaction["phase"] = "ready"
        write_json(self.override_journal, transaction)
        self.remove_stopped(transaction["previous_id"])
        self.report("config_ready", transaction)

    def restart_config(self, source):
        installed = read_json(self.journal)
        if source is None and not installed and not self.override.exists():
            return
        if source is None and (not installed or installed["phase"] != "ready"):
            current = inspect(self.name)
            if current and current["State"]["Running"] and self.test_config(container_env(current)) != self.expected_test_config():
                docker("stop", "--time", "20", current["Id"])
                raise InstallError("stopped unsafe DEV test config during an unfinished release")
            return
        require(installed and installed["phase"] == "ready", "install a release before changing DEV test config")
        release = Release(**installed["release"])
        pending = read_json(self.override_journal)
        if pending and pending["phase"] == "activating":
            self.continue_config(pending)
        elif pending and pending["phase"] == "restoring":
            old = self.override.read_text() if self.override.exists() else ""
            safe = not old or override_values(old.encode(), self.component)[0] > time.time()
            self.restore_transaction(pending, self.override_journal, restart_previous=safe)
        elif pending and pending["phase"] == "ready":
            self.remove_stopped(pending["previous_id"])

        old = self.override.read_text() if self.override.exists() else ""
        data = old if source is None and self.active_overrides() else ""
        if source not in (None, "-"):
            path = Path(source)
            require(path.is_absolute() and path.is_file() and path.stat().st_size <= 4096, "invalid DEV override input")
            data = path.read_text()
            expiry, _ = override_values(data.encode(), self.component)
            require(expiry > time.time(), "DEV override is already expired")
        current = inspect(self.name)
        require(current and current["Image"] == release.image_id and
                all((current["Config"]["Labels"] or {}).get(k) == v for k, v in release.labels().items()),
                "active release does not match the DEV config target")
        if old == data and self.test_config(container_env(current)) == self.expected_test_config():
            if source is not None:
                require(self.ready(current, release.sha), "active application readiness failed")
                self.report("config_already_ready")
            return
        transaction = self.new_transaction(release, current)
        transaction["override"] = data
        transaction["old_override"] = old
        write_json(self.override_journal, transaction)
        self.continue_config(transaction)


AI_LINK_PROBE = """
import os
import ssl
import urllib.error
import urllib.request
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None
ctx = ssl.create_default_context()
if os.environ.get('FOUNDATION_BACKEND_CA_FILE'):
    ctx.load_verify_locations(os.environ['FOUNDATION_BACKEND_CA_FILE'])
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                    urllib.request.HTTPSHandler(context=ctx), NoRedirect)
root = os.environ['FOUNDATION_BACKEND_URL'].rstrip('/')
with opener.open(root + '/health', timeout=5) as response:
    if response.status != 200:
        raise RuntimeError('backend health failed')
request = urllib.request.Request(root + '/internal/foundation/v1/snapshot',
                                headers={'X-Internal-Token': os.environ['AI_INTERNAL_TOKEN']})
try:
    opener.open(request, timeout=5)
except urllib.error.HTTPError as error:
    if error.code != 403:
        raise
else:
    raise RuntimeError('request without execution scope accepted')
bucket = os.environ.get('TRIAL_AUDIO_BUCKET')
if bucket:
    import boto3
    from botocore.config import Config
    storage = boto3.client('s3', endpoint_url=os.environ.get('TRIAL_S3_ENDPOINT') or None,
                          config=Config(connect_timeout=5, read_timeout=5, retries={'max_attempts': 0}))
    storage.list_object_versions(Bucket=bucket, Prefix='trial/', MaxKeys=1)
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("stage", "activate", "status", "restore", "baseline", "links",
                                           "restart-config", "expire-config"))
    parser.add_argument("environment", choices=("dev", "prod"))
    parser.add_argument("component", choices=CONFIG)
    parser.add_argument("params", nargs="*")
    args = parser.parse_args()
    os.umask(0o077)
    installer = None
    try:
        require(args.environment == "dev" or args.component != "miniapp", "Mini App release is DEV only")
        installer = Installer(args.environment, args.component)
        with installer.lock.open("a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            if args.action in ("baseline", "links"):
                require(len(args.params) == 1 and args.params[0] in ("legacy", "foundation"), "runtime family required")
                if args.action == "baseline":
                    installer.baseline(args.params[0])
                    installer.report("baseline_matches")
                else:
                    installer.links(args.params[0])
            elif args.action in ("restart-config", "expire-config"):
                require(args.environment == "dev" and args.component != "miniapp", "DEV backend config only")
                require(len(args.params) == (1 if args.action == "restart-config" else 0), "invalid DEV config parameters")
                installer.restart_config(args.params[0] if args.params else None)
            else:
                require(len(args.params) == (8 if args.action == "stage" else 7), "invalid release parameters")
                release = Release(*args.params[:7])
                release.validate()
                require(args.component != "miniapp" or release.family == "foundation", "Mini App requires Foundation")
                if args.action == "stage":
                    installer.stage(release, Path(args.params[7]))
                elif args.action == "activate":
                    installer.activate(release)
                elif args.action == "status":
                    installer.status(release)
                else:
                    installer.restore(release)
    except InstallError as error:
        if installer:
            try:
                installer.report("failed", read_json(installer.journal), reason=str(error))
            except (InstallError, OSError, subprocess.TimeoutExpired, ValueError):
                pass
        parser.exit(1, f"Release operation failed: {error}\n")
    except (OSError, subprocess.TimeoutExpired, ValueError):
        parser.exit(1, "Release operation failed: host command, configuration or dependency error\n")


if __name__ == "__main__":
    main()
