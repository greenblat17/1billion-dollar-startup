#!/usr/bin/env python3
"""Validate/import a reviewed catalog on the DEV host; publish only on explicit input."""

import hashlib
import json
import re
import ssl
import subprocess
import sys
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import HTTPSHandler, ProxyHandler, Request, build_opener


CLI = "com.eliteteam.speakingcoach.foundation.LearningCatalogCliKt"
VERSION = re.compile(r"[a-z0-9][a-z0-9._-]{0,79}\Z")
SHA256 = re.compile(r"[0-9a-f]{64}\Z")


def run_checked(args, stage):
    result = subprocess.run(args, capture_output=True, text=True, check=False)
    if result.returncode:
        # CLI and Docker stderr can contain host-specific details. Keep the failure stage only.
        raise RuntimeError(f"{stage}_failed")


def inspect_dev():
    result = subprocess.run(["docker", "inspect", "speaking-coach"], capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError("container_unavailable")
    container = json.loads(result.stdout)[0]
    values = dict(item.split("=", 1) for item in container["Config"]["Env"] if "=" in item)
    if not container["State"]["Running"] or values.get("SPEAKY_ENV") != "dev":
        raise RuntimeError("not_running_dev_container")
    if not values.get("DATABASE_URL") or not values.get("AI_INTERNAL_TOKEN"):
        raise RuntimeError("dev_database_or_service_token_missing")
    port = values.get("SERVER_PORT", "")
    if not port.isdecimal():
        raise RuntimeError("dev_port_missing")
    return values, port


def read_current(values, port):
    opener = build_opener(ProxyHandler({}), HTTPSHandler(context=ssl._create_unverified_context()))
    request = Request(f"https://127.0.0.1:{port}/internal/foundation/v1/catalog",
                      headers={"X-Internal-Token": values["AI_INTERNAL_TOKEN"]})
    try:
        with opener.open(request, timeout=20) as response:
            document = json.load(response)
    except HTTPError as error:
        if error.code == 400:
            try:
                body = json.load(error)
            except ValueError:
                body = {}
            if body.get("error") == "catalog_not_published":
                return None
        raise RuntimeError("catalog_read_failed") from None
    if document.get("ok") is not True or not isinstance(document.get("result"), dict):
        raise RuntimeError("catalog_read_failed")
    return document["result"]


def release(work):
    inputs = json.loads((work / "catalog-input.json").read_text())
    raw = (work / "catalog.json").read_bytes()
    expected_hash = inputs.get("sha256", "")
    version = inputs.get("version", "")
    if not SHA256.fullmatch(expected_hash) or hashlib.sha256(raw).hexdigest() != expected_hash:
        raise RuntimeError("catalog_sha256_mismatch")
    if not VERSION.fullmatch(version) or json.loads(raw)["version"] != version:
        raise RuntimeError("catalog_version_mismatch")
    publish = inputs.get("publish") is True
    reviewer = inputs.get("reviewer", "")
    reference = inputs.get("reviewReference", "")
    if publish and (not isinstance(reviewer, str) or not reviewer.strip() or
                    not isinstance(reference, str) or not reference.startswith("https://") or
                    len(reviewer) > 100 or len(reference) > 500):
        raise RuntimeError("catalog_review_required")

    values, port = inspect_dev()
    previous = read_current(values, port)
    container_path = "/tmp/speaky-reviewed-catalog.json"
    try:
        run_checked(["docker", "cp", str(work / "catalog.json"), f"speaking-coach:{container_path}"], "catalog_copy")
        command = ["docker", "exec", "speaking-coach", "java", "-cp", "/app/server-all.jar", CLI]
        run_checked(command + ["validate", container_path], "catalog_validate")
        run_checked(command + ["import", container_path], "catalog_import")
        if not publish:
            return {"version": version, "sha256": expected_hash, "imported": True, "published": False,
                    "previousCurrentVersion": previous.get("version") if previous else None}
        run_checked(command + ["publish", version, reviewer, reference], "catalog_publish")
    finally:
        subprocess.run(["docker", "exec", "speaking-coach", "rm", "-f", container_path],
                       capture_output=True, check=False)
    current = read_current(values, port)
    if current is None or current.get("version") != version or not current.get("publishedAt"):
        raise RuntimeError("catalog_publish_readback_failed")
    return {"version": version, "sha256": expected_hash, "imported": True, "published": True,
            "previousCurrentVersion": previous.get("version") if previous else None,
            "currentDigest": current.get("digest"), "topicCount": len(current.get("topics", []))}


if __name__ == "__main__":
    try:
        print(json.dumps(release(Path(sys.argv[1]).resolve()), ensure_ascii=True))
    except (IndexError, KeyError, OSError, ValueError, RuntimeError) as error:
        print(json.dumps({"error": str(error).split(":", 1)[0]}, ensure_ascii=True))
        raise SystemExit(1)
