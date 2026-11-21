#!/usr/bin/env python3
"""Check the running DEV container revision without printing its configuration."""

import json
import re
import subprocess
import sys


def main():
    component, expected = sys.argv[1:]
    container = {"ktor": "speaking-coach", "ai": "ai-service"}[component]
    info = json.loads(subprocess.check_output(["docker", "inspect", container], stderr=subprocess.DEVNULL))[0]
    config = info["Config"]
    env = dict(item.split("=", 1) for item in config["Env"] if "=" in item)
    revision = env.get("SPEAKY_RELEASE", "")
    label = (config.get("Labels") or {}).get("org.opencontainers.image.revision")
    if not info["State"]["Running"] or info["State"]["OOMKilled"] or env.get("SPEAKY_ENV") != "dev":
        raise SystemExit(f"{component}: expected a running DEV container")
    if revision != expected or (label and label != expected):
        observed = revision if re.fullmatch(r"[0-9a-f]{7,40}", revision) else "unknown"
        raise SystemExit(f"{component}: revision mismatch, expected {expected}, observed {observed}")
    print(f"{component}: verified DEV revision {expected}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, OSError, subprocess.SubprocessError):
        raise SystemExit("Could not read DEV container identity") from None
