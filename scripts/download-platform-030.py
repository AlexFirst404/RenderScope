"""Download only recorded public CDN URLs for current, saved 0.3.0 submissions.

No upload, authentication, UI operation or URL discovery is performed. Partial
ledgers are valid; absent links remain pending. Actual mismatches fail explicitly.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import time
import urllib.request
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
BASE = "distribution/platforms/0.3.0"
CDN_HOSTS = {
    "modrinth": {"cdn.modrinth.com"},
    "curseforge": {"edge.forgecdn.net", "media.forgecdn.net", "mediafilez.forgecdn.net"},
}
spec = importlib.util.spec_from_file_location("download_platform_verifier", Path(__file__).with_name("verify-platform-030.py"))
verifier = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verifier)


def now():
    return datetime.now(timezone.utc).isoformat()


def cdn_url(platform, value):
    verifier.https_url(value)
    parsed = urllib.parse.urlsplit(value)
    verifier.require(parsed.hostname in CDN_HOSTS[platform] and parsed.port in (None, 443) and not parsed.fragment,
                     "Only an observed HTTPS URL on the platform's exact CDN host allowlist is permitted")
    return value


class CdnRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, platform):
        self.platform = platform

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        cdn_url(self.platform, newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_download(platform, url, timeout):
    cdn_url(platform, url)
    request = urllib.request.Request(url, headers={
        "User-Agent": "RenderScope-0.3.0-public-download-verification",
        "Accept": "application/java-archive, application/octet-stream",
        "Accept-Encoding": "identity",
    }, method="GET")
    return urllib.request.build_opener(CdnRedirects(platform)).open(request, timeout=timeout)


def lock_path(root, platform):
    return root / ".tools/platform-downloads-030" / platform / "ledger.lock"


@contextmanager
def ledger_lock(root, platform, attempts=100, delay=0.05):
    path = lock_path(root, platform)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = None
    for attempt in range(attempts):
        try:
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            break
        except FileExistsError:
            if attempt + 1 == attempts:
                raise RuntimeError("Ledger lock is held; do not remove another writer's lock: " + path.as_posix())
            time.sleep(delay)
    try:
        os.write(descriptor, json.dumps({"pid": os.getpid(), "acquiredAt": now()}).encode("utf-8"))
        yield
    finally:
        os.close(descriptor)
        path.unlink()


def atomic_bytes(path, data, expected_previous=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(prefix=path.name + ".", suffix=".tmp", dir=path.parent, delete=False) as output:
            temporary = Path(output.name)
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        if expected_previous is not None:
            verifier.require(path.read_bytes() == expected_previous, "Ledger changed despite its lock; no overwrite performed")
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def json_bytes(document):
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def read_context(root, platform):
    manifest_path = root / BASE / "release-manifest.json"
    manifest_raw = manifest_path.read_bytes()
    manifest = json.loads(manifest_raw)
    manifest_sha = hashlib.sha256(manifest_raw).hexdigest()
    targets = verifier.manifest_targets(manifest, root)
    ledger_path = root / BASE / (platform + "-release-verification.json")
    # Root's CUA writer uses the same short lock, including its initial read.
    with ledger_lock(root, platform):
        ledger = json.loads(ledger_path.read_bytes()) if ledger_path.is_file() else None
    if ledger is None:
        return manifest_sha, targets, ledger_path, None, []
    checked = verifier.verify_platform(platform, manifest, manifest_sha, ledger, root)
    verifier.require(not checked["errors"], "Invalid current submission ledger: " + "; ".join(checked["errors"]))
    accepted = {row["identity"] for row in checked["files"] if row["submitted"] and row["metadataVerified"]}
    return manifest_sha, targets, ledger_path, ledger, accepted


def actual_file(path, target):
    size = path.stat().st_size
    verifier.require(size == target["bytes"], f"Actual cached byte size mismatch: {path.name}: {size} bytes")
    measured_sha = verifier.digest(path)
    verifier.require(size == target["bytes"] and measured_sha == target["sha256"],
                     f"Actual bytes mismatch: {path.name}: {size} bytes, SHA-256 {measured_sha}")
    return measured_sha, size


def cache_proof(root, platform, record, target, manifest_sha):
    folder = root / ".tools/platform-downloads-030" / platform
    cache = folder / target["fileName"]
    source = cache.with_name(cache.name + ".source.json")
    if not cache.is_file():
        return None
    measured_sha, size = actual_file(cache, target)
    already_recorded = (record.get("downloadedArtifact") == cache.relative_to(root).as_posix()
                        and record.get("downloadedSha256") == measured_sha and record.get("downloadedBytes") == size
                        and isinstance(record.get("downloadVerifiedAt"), str))
    provenance = json.loads(source.read_bytes()) if source.is_file() else {}
    verifier.require(isinstance(provenance, dict), "Download cache provenance is malformed")
    sourced = (provenance.get("identity") == record["identity"] and provenance.get("remoteId") == record["remoteId"]
               and provenance.get("downloadUrl") == record["downloadUrl"] and provenance.get("manifestSha256") == manifest_sha
               and provenance.get("downloadedSha256") == measured_sha and provenance.get("downloadedBytes") == size)
    if not already_recorded and not sourced:
        # Matching local upload bytes alone are not evidence of a remote GET.
        return None
    result = {"downloadedArtifact": cache.relative_to(root).as_posix(), "downloadedSha256": measured_sha,
              "downloadedBytes": size, "downloadVerifiedAt": record["downloadVerifiedAt"] if already_recorded else provenance["downloadVerifiedAt"],
              "downloadVerificationMethod": "rehash-verified-download-cache", "downloadCacheRehashedAt": now()}
    resolved = provenance.get("resolvedDownloadUrl") if sourced else record.get("resolvedDownloadUrl")
    if resolved:
        result["resolvedDownloadUrl"] = cdn_url(platform, resolved)
    return result


def fetch_actual(root, platform, record, target, manifest_sha, opener, timeout):
    url = cdn_url(platform, record["downloadUrl"])
    folder = root / ".tools/platform-downloads-030" / platform
    folder.mkdir(parents=True, exist_ok=True)
    cache = folder / target["fileName"]
    temporary = None
    try:
        with opener(platform, url, timeout) as response:
            verifier.require(response.status == 200, "CDN did not return HTTP 200")
            resolved = cdn_url(platform, response.geturl())
            length = response.headers.get("Content-Length")
            if length is not None:
                verifier.require(int(length) == target["bytes"], "CDN Content-Length differs from the current JAR")
            measured = hashlib.sha256()
            size = 0
            with tempfile.NamedTemporaryFile(prefix=cache.name + ".", suffix=".part", dir=folder, delete=False) as output:
                temporary = Path(output.name)
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    size += len(chunk)
                    verifier.require(size <= target["bytes"], "CDN response exceeds the expected artifact size")
                    measured.update(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            measured_sha = measured.hexdigest()
            verifier.require(size == target["bytes"] and measured_sha == target["sha256"],
                             f"Downloaded bytes mismatch: {size} bytes, SHA-256 {measured_sha}")
        os.replace(temporary, cache)
        temporary = None
        proof = {"downloadedArtifact": cache.relative_to(root).as_posix(), "downloadedSha256": measured_sha,
                 "downloadedBytes": size, "downloadVerifiedAt": now(), "resolvedDownloadUrl": resolved,
                 "downloadVerificationMethod": "actual-https-cdn-get"}
        atomic_bytes(cache.with_name(cache.name + ".source.json"), json_bytes({**proof, "identity": record["identity"],
            "remoteId": record["remoteId"], "downloadUrl": url, "manifestSha256": manifest_sha}))
        return proof
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def record_proof(root, platform, ledger_path, manifest_sha, original, proof, project_id):
    with ledger_lock(root, platform):
        verifier.require(hashlib.sha256((root / BASE / "release-manifest.json").read_bytes()).hexdigest() == manifest_sha,
                         "Release manifest changed during download; no proof recorded")
        previous = ledger_path.read_bytes()
        latest = json.loads(previous)
        verifier.require(latest.get("platform") == platform and latest.get("manifestSha256") == manifest_sha
                         and latest.get("schemaVersion") == 1 and latest.get("renderscopeVersion") == "0.3.0"
                         and latest.get("projectId") == project_id,
                         "Latest ledger release identity changed")
        matches = [row for row in latest.get("files", []) if row.get("identity") == original["identity"]]
        verifier.require(len(matches) == 1, "Latest ledger no longer has one matching target")
        current = matches[0]
        critical = ("identity", "loader", "mc", "sha256", "bytes", "file", "packagedArtifact", "expectedGameVersions",
                    "expectedLoaders", "javaRuntime", "clientOnly", "expectedFabricApi", "expectedReleaseChannel",
                    "submissionState", "remoteId", "remoteUrl", "downloadUrl", "metadataVerified", "metadataSource", "observedMetadata")
        verifier.require(all(current.get(field) == original.get(field) for field in critical),
                         "Submitted target, saved metadata or observed download URL changed; no proof recorded")
        current.update(proof)
        atomic_bytes(ledger_path, json_bytes(latest), expected_previous=previous)


def download_platform(platform, root=ROOT, opener=open_download, timeout=45):
    report = {"platform": platform, "success": True, "downloaded": 0, "cacheRehashed": 0,
              "pending": [], "files": [], "errors": []}
    try:
        manifest_sha, targets, ledger_path, ledger, accepted = read_context(root, platform)
        if ledger is None:
            report["pending"].append("No actual submission ledger exists yet")
            return report
        for record in ledger["files"]:
            identity = record["identity"]
            if identity not in accepted or not record.get("downloadUrl"):
                report["pending"].append(identity + ": saved submission/readback or observed CDN URL is pending")
                continue
            cdn_url(platform, record["downloadUrl"])
            target = targets[identity]
            proof = cache_proof(root, platform, record, target, manifest_sha)
            cached = proof is not None
            if not cached:
                proof = fetch_actual(root, platform, record, target, manifest_sha, opener, timeout)
            record_proof(root, platform, ledger_path, manifest_sha, record, proof, target[platform]["projectId"])
            report["cacheRehashed" if cached else "downloaded"] += 1
            report["files"].append({"identity": identity, **proof})
    except (ValueError, KeyError, TypeError, OSError, RuntimeError, http.client.HTTPException) as error:
        report["success"] = False
        report["errors"].append(str(error))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("modrinth", "curseforge", "both"), required=True)
    parser.add_argument("--timeout", type=int, default=45)
    args = parser.parse_args()
    verifier.require(1 <= args.timeout <= 120, "Timeout must be 1–120 seconds")
    reports = []
    for platform in verifier.PLATFORMS if args.platform == "both" else (args.platform,):
        report = download_platform(platform, timeout=args.timeout)
        reports.append(report)
        if not report["success"]:
            break
    print(json.dumps({"success": all(row["success"] for row in reports), "mode": "inbound public CDN GET only",
                      "platforms": reports}, ensure_ascii=False, indent=2))
    raise SystemExit(0 if all(row["success"] for row in reports) else 1)


if __name__ == "__main__":
    main()
