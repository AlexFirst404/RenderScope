"""Dry-run by default. Only the root operator may explicitly invoke --publish.

Requires a clean repository, an already-pushed v0.3.0 tag, all 67 current world
proofs, and authenticated gh. Never creates/pushes a tag, rebuilds, overwrites
an asset, or changes the publication preparer's timestamps or trust rules.

GitHub docs: https://cli.github.com/manual/gh_release_create
https://docs.github.com/en/rest/releases/assets#get-a-release-asset
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REPO = "AlexFirst404/RenderScope"
CLI_REPO = "github.com/" + REPO
TAG = "v0.3.0"
BASE = Path("distribution/platforms/0.3.0")
RECORD = BASE / "github-release-verification.json"


def run(*arguments: str) -> bytes:
    result = subprocess.run(arguments, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip() or "Command failed: " + arguments[0])
    return result.stdout


def load_module(name: str, path: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fingerprint(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def release_notes(raw: bytes) -> bytes:
    """Release pages do not resolve repository-relative Markdown links."""
    text = raw.decode("utf-8").replace("\r\n", "\n")
    for name in ("DEPENDENCIES.md", "release-manifest.json"):
        text = text.replace("](" + name + ")", "](https://github.com/" + REPO + "/blob/" + TAG + "/" + BASE.as_posix() + "/" + name + ")")
    return text.encode("utf-8")


def local_plan() -> dict:
    builder = load_module("github_build", "scripts/build-all.py")
    support = load_module("github_support", "scripts/generate-support-matrix.py")
    preparer = load_module("github_preparer", "scripts/prepare-publication-030.py")
    manifest_path = ROOT / BASE / "release-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    media_path = ROOT / BASE / "media-manifest.json"
    media = json.loads(media_path.read_text(encoding="utf-8"))["assets"]
    targets = builder.targets()
    rows = manifest["targets"]
    expected = {(t["minecraft"], t["loader"]) for t in targets}
    declared = {(t["minecraft"], t["loader"]): t for t in rows}
    errors = []
    if (len(targets) != 67 or len(rows) != 67 or set(declared) != expected
            or len({t["minecraft"] for t in targets}) != 23):
        errors.append("The manifest must contain exactly the configured 67 identities / 23 Minecraft releases")
    if (manifest.get("publicationReady") is not True or manifest.get("renderscopeVersion") != "0.3.0"
            or manifest.get("sourceRef") != TAG or manifest.get("releaseChannel") != "release"
            or manifest.get("gitEvidenceByteIdentity", {}).get("passed") is not True
            or any(manifest.get(key) != 67 for key in ("worldVerifiedTargets", "packageIndexMatchedTargets", "rendererOwnerRegressionVerifiedTargets"))):
        errors.append("The prepared manifest is not release-ready for v0.3.0")
    if run("git", "status", "--porcelain=v1", "--untracked-files=all").strip():
        errors.append("The repository must be clean, including staged and untracked files")
    origin = run("git", "remote", "get-url", "origin").decode().strip()
    if origin.lower().removesuffix(".git") not in (
            "https://github.com/" + REPO.lower(), "git@github.com:" + REPO.lower()):
        errors.append("origin must identify " + REPO)
    try:
        commit = run("git", "rev-parse", "--verify", TAG + "^{commit}").decode().strip()
    except RuntimeError:
        commit = None
        errors.append("The exact local release tag v0.3.0 does not exist")
    assets = []
    world_count = 0
    for target in targets:
        identity = target["loader"] + ":" + target["minecraft"]
        try:
            row = declared[(target["minecraft"], target["loader"])]
            audit = builder.audit(target)
            packaged = ROOT / row["packagedArtifact"]
            if (row["loaderVersion"] != target["loaderVersion"] or row["releaseChannel"] != "release"
                    or row["sha256"] != audit["sha256"] or row["bytes"] != audit["bytes"]
                    or packaged.name != row["fileName"] or packaged.stat().st_size != audit["bytes"]
                    or builder.digest(packaged) != audit["sha256"]):
                raise RuntimeError("Packaged JAR identity/hash/bytes do not match the current audited primary")
            assets.append({"name": packaged.name, "path": packaged.relative_to(ROOT).as_posix(),
                           "bytes": audit["bytes"], "sha256": audit["sha256"], "kind": "primary JAR"})
            if not preparer.ownership_regressions(target)["passed"]:
                raise RuntimeError("Actual ownership/block-replacement/export XML regressions are not passing")
            evidence = support.inspect_runtime(target, audit["sha256"])
            world_count += bool(evidence)
            evidence_keys = lambda values: {(v["path"], v["sha256"], v["environment"]) for v in values}
            if not evidence or evidence_keys(evidence) != evidence_keys(row["runtimeEvidence"]):
                raise RuntimeError("Current world evidence is missing or differs from the prepared manifest")
        except (RuntimeError, KeyError, OSError, ValueError) as error:
            errors.append(identity + ": " + str(error))
    if len({asset["name"] for asset in assets}) != len(assets):
        errors.append("GitHub flat asset names must be unique")
    checksums = "".join(f"{asset['sha256']}  {asset['name']}\n" for asset in sorted(assets, key=lambda a: a["name"])).encode("ascii")
    files = preparer.git_evidence_files(rows, media)
    files += [{"path": asset["path"], "sha256": asset["sha256"], "role": "release primary JAR"} for asset in assets]
    for relative in (BASE / "release-manifest.json", BASE / "media-manifest.json", BASE / "fabric-api-dependencies.json",
                     Path("distribution/0.3.0/artifacts.json")):
        files.append({"path": relative.as_posix(), "sha256": fingerprint((ROOT / relative).read_bytes()), "role": "release metadata"})
    notes_path = BASE / "GITHUB_RELEASE_0.3.0.md"
    source_notes = (ROOT / notes_path).read_bytes().replace(b"\r\n", b"\n")
    notes = release_notes(source_notes)
    if b"DRAFT" in notes or "ЧЕРНОВИК".encode() in notes or "## Русский".encode() not in notes:
        errors.append("Release notes must be bilingual final text, without DRAFT status")
    if commit:
        identity = preparer.git_evidence_identity(files, TAG)
        errors += [f"Tag/raw bytes differ: {entry['path']} ({entry['reason']})"
                   for entry in identity["files"] if not entry["passed"]]
        if run("git", "show", TAG + ":" + notes_path.as_posix()).replace(b"\r\n", b"\n") != source_notes:
            errors.append("Release notes do not match the actual v0.3.0 Git blob")
    assets.append({"name": "SHA256SUMS", "bytes": len(checksums), "sha256": fingerprint(checksums),
                   "kind": "flat release checksums", "content": checksums})
    return {"ready": not errors, "errors": errors, "tag": TAG, "tagCommit": commit,
            "repository": REPO, "worldVerifiedTargets": world_count, "primaryJarCount": len(assets) - 1,
            "assets": assets, "notes": notes, "manifestSha256": fingerprint(manifest_path.read_bytes()),
            "sourceNotesSha256": fingerprint(source_notes),
            "scopes": sorted({e["environment"] for row in rows for e in row["runtimeEvidence"]})}


class GitHub:
    def __init__(self):
        self.executable = shutil.which("gh")
        if not self.executable:
            raise RuntimeError("Authenticated GitHub CLI (gh) is required")

    def call(self, *arguments: str) -> bytes:
        return run(self.executable, *arguments)

    def release(self) -> dict | None:
        result = subprocess.run([self.executable, "api", "--hostname", "github.com", f"repos/{REPO}/releases/tags/{TAG}"], cwd=ROOT,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode and b"HTTP 404" in result.stderr:
            return None
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
        return json.loads(result.stdout)

    def assets(self, release: dict) -> list[dict]:
        assets, page = [], 1
        while True:
            batch = json.loads(self.call("api", "--hostname", "github.com", f"repos/{REPO}/releases/{release['id']}/assets?per_page=100&page={page}"))
            assets += batch
            if len(batch) < 100:
                return assets
            page += 1

    def download(self, asset: dict) -> bytes:
        return self.call("api", "--hostname", "github.com", "-H", "Accept: application/octet-stream", f"repos/{REPO}/releases/assets/{asset['id']}")


def verify_remote_assets(expected: list[dict], remote: list[dict], download) -> tuple[list[dict], list[dict]]:
    names = [asset["name"] for asset in remote]
    wanted = {asset["name"]: asset for asset in expected}
    if len(set(names)) != len(names) or set(names) - set(wanted):
        raise RuntimeError("Existing release has duplicate or unexpected assets; refusing to overwrite/delete")
    found, verified = set(), []
    for asset in remote:
        local = wanted[asset["name"]]
        if asset["state"] != "uploaded" or asset["size"] != local["bytes"]:
            raise RuntimeError("Remote asset state/size mismatch: " + asset["name"])
        digest = asset.get("digest")
        if digest:
            if digest != "sha256:" + local["sha256"]:
                raise RuntimeError("Remote asset SHA-256 mismatch: " + asset["name"])
            method = "GitHub API SHA-256 digest"
        else:
            raw = download(asset)
            if len(raw) != local["bytes"] or fingerprint(raw) != local["sha256"]:
                raise RuntimeError("Downloaded remote bytes mismatch: " + asset["name"])
            method = "downloaded bytes SHA-256"
        found.add(asset["name"])
        verified.append({"name": asset["name"], "id": asset["id"], "bytes": asset["size"],
                         "sha256": local["sha256"], "remoteDigest": digest, "verifiedBy": method,
                         "url": asset.get("browser_download_url")})
    return [asset for asset in expected if asset["name"] not in found], verified


def remote_tag_matches(commit: str) -> None:
    lines = run("git", "ls-remote", "--tags", "origin", "refs/tags/" + TAG, "refs/tags/" + TAG + "^{}").decode().splitlines()
    refs = dict(line.split(None, 1)[::-1] for line in lines)
    actual = refs.get("refs/tags/" + TAG + "^{}", refs.get("refs/tags/" + TAG))
    if actual != commit:
        raise RuntimeError("The pushed release tag does not resolve to the validated local commit")


def publish(plan: dict, github: GitHub) -> dict:
    """Only called by main after explicit --publish and successful local gates."""
    if not plan["ready"] or plan["primaryJarCount"] != 67 or len(plan["assets"]) != 68:
        raise RuntimeError("Local release gate is not ready")
    release = github.release()
    with tempfile.TemporaryDirectory(prefix="renderscope-gh-release-") as directory:
        temporary = Path(directory)
        notes = temporary / "release-notes.md"
        notes.write_bytes(plan["notes"])
        checksums = temporary / "SHA256SUMS"
        checksums.write_bytes(plan["assets"][-1]["content"])
        if release is None:
            github.call("release", "create", TAG, "--repo", CLI_REPO, "--draft", "--verify-tag",
                        "--title", "RenderScope 0.3.0", "--notes-file", str(notes))
            release = github.release()
        if release is None or release["tag_name"] != TAG:
            raise RuntimeError("GitHub did not return the exact release tag")
        missing, verified = verify_remote_assets(plan["assets"], github.assets(release), github.download)
        if not release["draft"] and (missing or release["prerelease"] or release.get("name") != "RenderScope 0.3.0" or
                release.get("body", "").replace("\r\n", "\n") != plan["notes"].decode("utf-8")):
            raise RuntimeError("Existing public release differs from the validated complete release")
        for asset in missing:
            source = checksums if asset["name"] == "SHA256SUMS" else ROOT / asset["path"]
            print("Uploading missing asset: " + asset["name"], flush=True)
            github.call("release", "upload", TAG, str(source), "--repo", CLI_REPO)
        missing, verified = verify_remote_assets(plan["assets"], github.assets(release), github.download)
        if missing:
            raise RuntimeError("Remote release is incomplete; draft left unpublished")
        if release["draft"]:
            github.call("release", "edit", TAG, "--repo", CLI_REPO, "--draft=false", "--prerelease=false", "--latest",
                        "--title", "RenderScope 0.3.0", "--notes-file", str(notes))
        final = github.release()
        if (not final or final["draft"] or final["prerelease"] or final["tag_name"] != TAG or final.get("name") != "RenderScope 0.3.0"
                or final.get("body", "").replace("\r\n", "\n") != plan["notes"].decode("utf-8")):
            raise RuntimeError("Final public release state/notes could not be verified")
        missing, verified = verify_remote_assets(plan["assets"], github.assets(final), github.download)
        if missing:
            raise RuntimeError("Final public asset inventory is incomplete")
    return {"schemaVersion": 1, "verifiedAt": datetime.now(timezone.utc).isoformat(), "success": True,
            "repository": REPO, "tag": TAG, "tagCommit": plan["tagCommit"], "releaseUrl": final["html_url"],
            "releaseId": final["id"], "published": True, "prerelease": False, "primaryJarCount": 67,
            "assetCount": len(verified), "manifestSha256": plan["manifestSha256"],
            "notesSha256": fingerprint(plan["notes"]), "sourceNotesSha256": plan["sourceNotesSha256"],
            "notesTransform": "Only repository-relative dependency/evidence links become absolute release-tag URLs; line endings are LF",
            "runtimeScopes": plan["scopes"], "assets": verified,
            "meaning": "67 exact primary JARs plus flat SHA256SUMS; API digest or actual downloaded bytes checked. Source archives are GitHub-generated, not uploaded QA artifacts."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--publish", action="store_true", help="Explicit root-operator action: upload and publish after every gate passes")
    args = parser.parse_args()
    plan = local_plan()
    print(json.dumps({key: plan[key] for key in ("ready", "errors", "tag", "tagCommit", "repository", "worldVerifiedTargets", "primaryJarCount", "scopes")}
                     | {"mode": "publish" if args.publish else "dry-run", "plannedAssetCount": len(plan["assets"])}, indent=2), flush=True)
    if not plan["ready"]:
        return 2
    remote_tag_matches(plan["tagCommit"])
    github = GitHub()
    if not args.publish:
        release = github.release()
        missing, verified = verify_remote_assets(plan["assets"], github.assets(release) if release else [], github.download)
        print(json.dumps({"remoteChecked": True, "releaseExists": release is not None,
                          "matchingAssets": len(verified), "missingAssets": len(missing), "writesPerformed": False}, indent=2))
        return 0
    record = publish(plan, github)
    path = ROOT / RECORD
    temporary = path.with_suffix(".tmp")
    temporary.write_bytes((json.dumps(record, indent=2) + "\n").encode("utf-8"))
    temporary.replace(path)
    print(json.dumps({"success": True, "releaseUrl": record["releaseUrl"], "record": RECORD.as_posix(), "assetsVerified": record["assetCount"]}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (RuntimeError, OSError, ValueError, KeyError) as error:
        print("GitHub release refused/failed: " + str(error), file=sys.stderr)
        raise SystemExit(2)
