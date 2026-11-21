"""Install verified release images over SSH and record the outcome."""

import argparse
import json
import os
import re
import subprocess
import tempfile
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlsplit

from release import load_json, require, select_components, stream_sha256, write_json

READINESS_TIMEOUT = 30
HOST = re.compile(r"[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}")
USER = re.compile(r"[a-z_][a-z0-9_-]{0,31}")


class RolloutError(RuntimeError):
    pass


class Host:
    def __init__(self, role, environment, directory, known_hosts):
        self.role = role
        prefix = f"SPEAKY_RELEASE_{role.upper()}"
        address = os.environ[f"{prefix}_HOST"]
        user = os.environ[f"{prefix}_USER"]
        if not HOST.fullmatch(address) or not USER.fullmatch(user):
            raise RolloutError(f"Invalid SSH target for {role}")
        self.target = f"{user}@{address}"
        key = directory / f"{role}.key"
        key.write_text(os.environ[f"{prefix}_SSH_KEY"].rstrip() + "\n")
        key.chmod(0o600)
        alias = "cmp" if role == "ktor" else role
        self.options = [
            "-i", str(key), "-o", "BatchMode=yes", "-o", "IdentitiesOnly=yes",
            "-o", "StrictHostKeyChecking=yes", "-o", f"UserKnownHostsFile={known_hosts}",
            "-o", f"HostKeyAlias=speaky-{environment}-{alias}", "-o", "ConnectTimeout=10",
            "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3",
        ]
        self.remote = None

    def command(self, *args, timeout=150, operation="SSH", host_report=False):
        process = subprocess.run(
            ["ssh", *self.options, self.target, *args], text=True, capture_output=True,
            timeout=timeout,
        )
        if process.returncode:
            reason = ""
            if host_report and process.stdout.strip():
                try:
                    detail = json.loads(process.stdout.splitlines()[-1]).get("reason", "")
                    if re.fullmatch(r"[A-Za-z0-9 .:_/-]{1,160}", detail):
                        reason = f": {detail}"
                except (TypeError, ValueError, AttributeError):
                    pass
            raise RolloutError(f"{self.role} {operation} failed (exit {process.returncode}){reason}")
        return process.stdout.strip()

    def copy(self, local, remote):
        process = subprocess.run(
            ["scp", *self.options, str(local), f"{self.target}:{remote}"],
            capture_output=True, timeout=600,
        )
        if process.returncode:
            raise RolloutError(f"{self.role} file transfer failed")

    def prepare(self):
        self.remote = self.command("mktemp", "-d", "/tmp/speaky-release.XXXXXXXXXX")
        if not re.fullmatch(r"/tmp/speaky-release\.[a-zA-Z0-9]{10}", self.remote):
            self.remote = None
            raise RolloutError("Unexpected remote release directory")
        self.copy(Path(__file__).with_name("host-install.py"), f"{self.remote}/install.py")

    def invoke(self, method, environment, component, *args):
        result = self.command("python3", f"{self.remote}/install.py", method,
                              environment, component, *args, operation=f"{component} {method}",
                              timeout=600 if method == "stage" else 150, host_report=True)
        if result:
            # Host operations emit a small JSON result without config, tokens or raw logs.
            try:
                record = json.loads(result.splitlines()[-1])
            except (IndexError, ValueError):
                raise RolloutError(f"Invalid host response for {component} {method}") from None
            print(f"{component} {method}: {record['outcome']}", flush=True)
            fields = ("outcome", "image_id", "previous_image_id", "source_sha", "running")
            return {key: record[key] for key in fields if key in record}
        return {}

    def close(self):
        if self.remote:
            try:
                self.command("rm", "-rf", "--", self.remote, timeout=30)
            except (RolloutError, subprocess.TimeoutExpired):
                print(f"{self.role} temporary file cleanup failed", flush=True)


def miniapp_edge(expected_asset):
    base = os.environ["SPEAKY_RELEASE_MINIAPP_URL"].rstrip("/")
    parsed = urlsplit(base)
    require(parsed.scheme == "https" and parsed.netloc and not parsed.query and not parsed.fragment,
            "Mini App preflight requires its HTTPS URL")
    for path in ("", "/assets/app.js"):
        request = urllib.request.Request(base + path, headers={"Cache-Control": "no-cache"})
        with urllib.request.urlopen(request, timeout=READINESS_TIMEOUT) as response:
            if path and expected_asset:
                require(stream_sha256(response) == expected_asset,
                        "Mini App edge did not return the selected asset")


def arguments(release, component):
    image = release["images"][component]
    return (str(release["build_run_id"]), str(release["build_run_attempt"]),
            release["source_sha"], release["runtime_family"], image["image_id"],
            image["platform"], image["sha256"])


def apply(args):
    selected = select_components(args.environment, args.components)
    release = load_json(args.directory / "release.json")
    report = {"environment": args.environment, "release": release["build_run_id"],
              "attempt": release["build_run_attempt"], "sha": release["source_sha"],
              "components": {component: {"status": "pending", "target_image": release["images"][component]["image_id"]}
                             for component in selected}}
    family = release["runtime_family"]
    backends = ("ktor", "ai") if {"ktor", "ai"}.intersection(selected) else ()
    attempted = []
    activated = {}
    hosts = {}

    def invoke(component, method, *params):
        host = hosts["ktor" if component == "ktor" else "ai"]
        return host.invoke(method, args.environment, component, *params)

    with tempfile.TemporaryDirectory() as directory:
        try:
            require(os.environ.get("SPEAKY_RELEASE_ENABLED") == "true",
                    "Release workflow activation is pending for this environment")
            require(os.environ.get("SPEAKY_RELEASE_RUNTIME_FAMILY") == family,
                    "Release family differs from the approved environment runtime")
            directory = Path(directory)
            pins = os.environ.get("SPEAKY_RELEASE_KNOWN_HOSTS", "")
            require(pins.strip(), "Configure the pinned SSH host keys for this environment")
            known_hosts = directory / "known_hosts"
            known_hosts.write_text(pins.rstrip() + "\n")
            for role in ("ktor", "ai") if backends else ("ai",):
                hosts[role] = Host(role, args.environment, directory, known_hosts)
                hosts[role].prepare()
            for component in backends:
                if component not in selected:
                    invoke(component, "baseline", family)
            if "miniapp" in selected:
                miniapp_edge(None)

            for component in selected:
                host = hosts["ktor" if component == "ktor" else "ai"]
                archive = f"{host.remote}/{component}.tar.gz"
                host.copy(args.directory / f"{component}.tar.gz", archive)
                result = invoke(component, "stage", *arguments(release, component), archive)
                report["components"][component].update(status="staged", before_image=result.get("image_id"))

            for component in selected:
                attempted.append(component)
                current = invoke(component, "activate", *arguments(release, component))
                activated[component] = current["outcome"]
                report["components"][component].update(current, status="activated")

            for component in backends:
                invoke(component, "links", family)
            if "miniapp" in selected:
                miniapp_edge(release["images"]["miniapp"]["edge_app_sha256"])
            for component in selected:
                report["components"][component]["status"] = "ready"
        except (RolloutError, OSError, KeyError, ValueError, subprocess.TimeoutExpired) as error:
            failure = str(error) if isinstance(error, (RolloutError, ValueError)) else type(error).__name__
            # urllib errors and Config errors can include private hostnames or paths.
            report["error"] = type(error).__name__ if isinstance(error, urllib.error.URLError) else failure
            for component in reversed(attempted):
                outcome = report["components"][component]
                try:
                    if activated.get(component) == "already_ready":
                        outcome["recovery"] = "unchanged"
                    elif component not in activated:
                        outcome["recovery"] = "activation_outcome_uncertain"
                    elif "ktor" in attempted and activated.get("ktor") != "already_ready":
                        outcome["recovery"] = "operator_schema_check_required"
                    else:
                        restore = invoke(component, "restore", *arguments(release, component))
                        outcome["recovery"] = restore.get("outcome", "restore_checked")
                except (RolloutError, OSError, ValueError, KeyError, subprocess.TimeoutExpired):
                    outcome["recovery"] = "unverified"
                try:
                    outcome["observed"] = invoke(component, "status", *arguments(release, component))
                except (RolloutError, OSError, ValueError, KeyError, subprocess.TimeoutExpired):
                    outcome["observed"] = {"outcome": "unverified"}
            print(f"Rollout stopped: {report['error']}", flush=True)
            return False
        finally:
            write_json(args.directory / "rollout.json", report)
            for host in hosts.values():
                host.close()

    if args.environment == "dev":
        write_json(args.directory / "receipt.json", {
            **release, "environment": "dev",
            "images": {component: release["images"][component] for component in selected},
        })
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("environment", choices=("dev", "prod"))
    parser.add_argument("components")
    parser.add_argument("directory", type=Path)
    return 0 if apply(parser.parse_args()) else 1


if __name__ == "__main__":
    raise SystemExit(main())
