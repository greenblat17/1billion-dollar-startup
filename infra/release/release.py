"""Build and verify Speaky release artifacts. This tool has no SSH access."""

import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

PROFILES = {
    "foundation": ("ktor", "ai", "miniapp"),
    "legacy": ("ktor", "ai"),
}
PLATFORM = "linux/amd64"
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"[0-9a-f]{64}")
ARCHIVE = "image.tar.gz"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def identifier(value):
    require(type(value) is int and value > 0, "Expected a positive GitHub ID")
    return value


def full_sha(value):
    require(isinstance(value, str) and SHA.fullmatch(value), "Expected a full Git SHA")
    return value


def digest(value):
    require(isinstance(value, str) and DIGEST.fullmatch(value), "Expected a SHA-256 digest")
    return value


def stream_sha256(stream):
    checksum = hashlib.sha256()
    for block in iter(lambda: stream.read(1024 * 1024), b""):
        checksum.update(block)
    return checksum.hexdigest()


def sha256(path):
    with path.open("rb") as stream:
        return stream_sha256(stream)


def load_json(path):
    with path.open() as stream:
        return json.load(stream)


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n")


def runtime_family(source):
    dockerfile = (source / "ai-service/Dockerfile").read_text()
    kt = list((source / "cmp/server/src/main").rglob("Application.kt"))
    require(len(kt) == 1, "Cannot identify the Ktor entrypoint")
    server = kt[0].read_text()
    if '"app.speaky_main:create_app"' in dockerfile and "startSpeakyServer(" in server:
        return "foundation"
    if '"app.main:create_app"' in dockerfile and "startSpeakyServer(" not in server:
        return "legacy"
    raise ValueError("Ktor and AI must use the same known runtime family")


def select_components(environment, requested):
    allowed = PROFILES["foundation"] if environment == "dev" else PROFILES["legacy"]
    if requested == "all":
        return allowed
    selected = [part.strip() for part in requested.split(",")]
    require(set(selected) <= set(allowed) and len(selected) == len(set(selected)),
            "Select each allowed component at most once")
    return tuple(component for component in allowed if component in selected)


def archive_image(image, output):
    with tempfile.TemporaryFile() as error:
        with subprocess.Popen(["docker", "save", image], stdout=subprocess.PIPE, stderr=error) as process:
            with gzip.open(output, "wb", compresslevel=3) as stream:
                shutil.copyfileobj(process.stdout, stream)
            if process.wait():
                error.seek(0)
                raise ValueError(f"docker save failed: {error.read().decode().strip()}")


def pack(args):
    info = json.loads(subprocess.check_output(["docker", "image", "inspect", args.image]))[0]
    image_id = info["Id"]
    require(image_id.startswith("sha256:"), "Missing Docker image ID")
    digest(image_id.removeprefix("sha256:"))
    require(f"{info['Os']}/{info['Architecture']}" == PLATFORM, f"Expected {PLATFORM}")
    args.directory.mkdir(parents=True, exist_ok=True)
    archive = args.directory / ARCHIVE
    archive_image(args.image, archive)
    descriptor = {"image_id": image_id, "sha256": sha256(archive), "platform": PLATFORM}
    if (info["Config"].get("Labels") or {}).get("io.speaky.component") == "miniapp":
        asset = subprocess.check_output([
            "docker", "run", "--rm", "--network", "none", "--entrypoint", "cat", image_id,
            "/usr/share/nginx/html/miniapp/assets/app.js",
        ])
        descriptor["edge_app_sha256"] = hashlib.sha256(asset).hexdigest()
    write_json(args.directory / "descriptor.json", descriptor)


def make_manifest(args):
    image_ids = dict(part.split("=", 1) for part in args.artifacts.split())
    require(set(image_ids) == set(PROFILES[args.family]), "Unexpected Build component IDs")
    images = {}
    for component, artifact_id in image_ids.items():
        descriptor = load_json(args.directory / component / "descriptor.json")
        images[component] = {**descriptor, "artifact_id": identifier(int(artifact_id))}
    write_json(args.output, {
        "schema_version": 1, "source_sha": full_sha(args.sha), "runtime_family": args.family,
        "build_run_id": identifier(args.run), "build_run_attempt": identifier(args.attempt),
        "images": images,
    })


class GitHub:
    def __init__(self, repo):
        self.repo = repo

    def request(self, route):
        return json.loads(subprocess.check_output(
            ["gh", "api", f"repos/{self.repo}/actions/{route}"]
        ))

    def download(self, artifact, member, maximum=None, output=None):
        with tempfile.TemporaryFile() as archive:
            subprocess.run(
                ["gh", "api", f"repos/{self.repo}/actions/artifacts/{artifact['id']}/zip"],
                stdout=archive, check=True,
            )
            archive.seek(0)
            expected = artifact.get("digest")
            if expected:
                actual = stream_sha256(archive)
                require(expected == f"sha256:{actual}", "GitHub artifact archive checksum mismatch")
                archive.seek(0)
            with zipfile.ZipFile(archive) as zipped:
                require(zipped.namelist() == [member], f"Artifact must contain only {member}")
                if output:
                    with zipped.open(member) as source, output.open("wb") as target:
                        shutil.copyfileobj(source, target)
                    return None
                require(zipped.getinfo(member).file_size <= maximum, "Artifact JSON is too large")
                return json.loads(zipped.read(member))

    def run(self, run_id, workflow, *, main_push=False):
        run = self.request(f"runs/{identifier(run_id)}")
        workflow_id = self.request(f"workflows/{workflow}")["id"]
        require(run["repository"]["full_name"].lower() == self.repo.lower() and
                run["head_repository"]["full_name"].lower() == self.repo.lower() and
                run["workflow_id"] == workflow_id and run["path"] == f".github/workflows/{workflow}" and
                run["head_branch"] == "main" and run["conclusion"] == "success",
                "Expected a successful run of the trusted main workflow")
        if main_push:
            require(run["event"] == "push", "PROD requires a release built by a push to main")
        else:
            require(run["event"] in {"push", "workflow_dispatch"}, "Unexpected workflow trigger")
        return run

    def artifacts(self, run_id):
        return self.request(f"runs/{run_id}/artifacts?per_page=100")["artifacts"]


def named_artifact(artifacts, name):
    matching = [item for item in artifacts if item["name"] == name]
    require(len(matching) == 1 and not matching[0]["expired"], f"Missing or expired artifact {name}")
    identifier(matching[0]["id"])
    return matching[0]


def validate_manifest(release, run, selected, artifacts):
    require(release["schema_version"] == 1, "Unsupported release schema")
    require(identifier(release["build_run_id"]) == run["id"] and
            identifier(release["build_run_attempt"]) == run["run_attempt"],
            "Release does not match the successful build attempt")
    source_sha = full_sha(release["source_sha"])
    if run["event"] == "push":
        require(source_sha == run["head_sha"], "Release SHA differs from the push SHA")
    family = release["runtime_family"]
    require(family in PROFILES and set(release["images"]) == set(PROFILES[family]),
            "Release components do not match the runtime family")
    require(set(selected) <= set(release["images"]), "Selected component is absent from the release")
    for component, image in release["images"].items():
        artifact = named_artifact(artifacts, f"speaky-{component}-{run['run_attempt']}")
        require(identifier(image["artifact_id"]) == artifact["id"], "Mismatched image artifact ID")
        require(image["platform"] == PLATFORM, f"Expected {PLATFORM}")
        require(image["image_id"].startswith("sha256:"), "Missing Docker image ID")
        digest(image["image_id"].removeprefix("sha256:"))
        digest(image["sha256"])
        if component == "miniapp":
            digest(image["edge_app_sha256"])


def receipt_matches(receipt, release, selected):
    return (receipt["schema_version"] == 1 and receipt["environment"] == "dev" and
            all(receipt[key] == release[key] for key in
                ("build_run_id", "build_run_attempt", "source_sha", "runtime_family")) and
            all(receipt["images"].get(component) == release["images"][component]
                for component in selected))


def require_dev_receipt(github, release, selected):
    run_id = release["build_run_id"]
    for page in range(1, 11):
        runs = github.request(
            f"workflows/redeploy.yml/runs?branch=main&event=workflow_dispatch"
            f"&status=success&per_page=100&page={page}"
        )["workflow_runs"]
        for candidate in runs:
            if candidate["display_title"] != f"DEV release {run_id}":
                continue
            run = github.run(candidate["id"], "redeploy.yml")
            require(run["event"] == "workflow_dispatch", "Invalid DEV receipt trigger")
            artifact_name = f"speaky-dev-receipt-{run['run_attempt']}"
            artifacts = github.artifacts(run["id"])
            if not any(item["name"] == artifact_name and not item["expired"] for item in artifacts):
                continue
            artifact = named_artifact(artifacts, artifact_name)
            receipt = github.download(artifact, "receipt.json", maximum=65536)
            if receipt_matches(receipt, release, selected):
                print(f"Verified DEV receipt from run {run['id']}")
                return
        if len(runs) < 100:
            break
    raise ValueError("No successful DEV rollout covers this exact build attempt and component set")


def fetch(args):
    github = GitHub(args.repo)
    selected = select_components(args.environment, args.components)
    run = github.run(args.run, "build-release.yml", main_push=args.environment == "prod")
    artifacts = github.artifacts(run["id"])
    artifact = named_artifact(artifacts, f"speaky-release-{run['run_attempt']}")
    release = github.download(artifact, "release.json", maximum=65536)
    validate_manifest(release, run, selected, artifacts)
    if args.environment == "prod":
        require_dev_receipt(github, release, selected)
    args.directory.mkdir(parents=True, exist_ok=True)
    for component in selected:
        image = release["images"][component]
        target = args.directory / f"{component}.tar.gz"
        artifact = next(item for item in artifacts if item["id"] == image["artifact_id"])
        github.download(artifact, ARCHIVE, output=target)
        require(sha256(target) == image["sha256"], f"{component} image archive checksum mismatch")
    write_json(args.directory / "release.json", release)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    profile = sub.add_parser("family")
    profile.add_argument("source", type=Path)
    profile.set_defaults(invoke=lambda args: print(runtime_family(args.source)))
    package = sub.add_parser("pack")
    package.add_argument("image")
    package.add_argument("directory", type=Path)
    package.set_defaults(invoke=pack)
    manifest = sub.add_parser("manifest")
    manifest.add_argument("directory", type=Path)
    manifest.add_argument("output", type=Path)
    manifest.add_argument("family", choices=PROFILES)
    manifest.add_argument("sha")
    manifest.add_argument("run", type=int)
    manifest.add_argument("attempt", type=int)
    manifest.add_argument("artifacts")
    manifest.set_defaults(invoke=make_manifest)
    download = sub.add_parser("fetch")
    download.add_argument("repo")
    download.add_argument("environment", choices=("dev", "prod"))
    download.add_argument("run", type=int)
    download.add_argument("components")
    download.add_argument("directory", type=Path)
    download.set_defaults(invoke=fetch)
    args = parser.parse_args()
    try:
        args.invoke(args)
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile,
            subprocess.CalledProcessError) as error:
        print(f"Release rejected: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
