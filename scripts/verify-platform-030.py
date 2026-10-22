"""Offline checks of actual 0.3.0 UI submission ledgers; never publishes or downloads.

Submission, saved metadata, recorded download fingerprints and raw moderation
status are separate facts. Test fixtures are temporary and are never ledgers.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime
import hashlib
import json
from pathlib import Path, PureWindowsPath
import re
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
PLATFORMS = ("modrinth", "curseforge")
BASE = "distribution/platforms/0.3.0"
SHA = re.compile(r"[0-9a-f]{64}")


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative_path(root, value):
    require(isinstance(value, str) and bool(value) and not Path(value).is_absolute()
            and not PureWindowsPath(value).drive, "Expected a repository-relative path")
    path = (root / value).resolve()
    require(path.is_relative_to(root.resolve()), "Path escapes the repository")
    return path


def timestamp(value, field):
    require(isinstance(value, str), field + " is missing")
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    require(parsed.tzinfo is not None, field + " must include its timezone")


def https_url(value, platform=None):
    require(isinstance(value, str), "Missing remote URL")
    parsed = urlsplit(value)
    require(parsed.scheme == "https" and bool(parsed.hostname) and not parsed.username and not parsed.password,
            "Expected an absolute HTTPS URL")
    if platform:
        domain = platform + ".com"
        require(parsed.hostname == domain or parsed.hostname.endswith("." + domain), "URL belongs to another platform")


def manifest_targets(manifest, root):
    require(isinstance(manifest, dict), "Current release manifest must be an object")
    require(manifest.get("renderscopeVersion") == "0.3.0" and manifest.get("publicationReady") is True,
            "Current release manifest has not passed publicationReady")
    require(manifest.get("releaseChannel") == "release" and manifest.get("artifactCount") == 67,
            "Expected the 67-artifact Release manifest")
    rows = manifest.get("targets")
    require(isinstance(rows, list) and len(rows) == 67, "Expected exactly 67 current manifest targets")
    targets = {}
    for row in rows:
        require(isinstance(row, dict), "Manifest target must be an object")
        identity = row["loader"] + ":" + row["minecraft"]
        require(identity not in targets and row["loader"] in ("fabric", "forge", "neoforge"), "Duplicate or unknown manifest identity")
        require(isinstance(row.get("sha256"), str) and SHA.fullmatch(row["sha256"]), "Invalid current artifact SHA-256")
        require(type(row.get("bytes")) is int and row["bytes"] > 0, "Invalid current artifact size")
        require(row.get("clientSide") == "required" and row.get("serverSide") == "unsupported", "Manifest is not client-only")
        require(row.get("releaseChannel") == "release", "Target is not a Release")
        require(type(row.get("javaRuntime")) is int and row["javaRuntime"] in (21, 25), "Invalid Java runtime")
        for field in ("artifact", "packagedArtifact"):
            path = relative_path(root, row[field])
            require(path.is_file() and path.stat().st_size == row["bytes"] and digest(path) == row["sha256"],
                    "Current " + field + " bytes differ: " + identity)
            require(path.name == row["fileName"] and path.suffix == ".jar", "Wrong artifact filename: " + identity)
        targets[identity] = row
    return targets


def required_dependencies(platform, target, observed):
    dependencies = observed.get("requiredDependencies")
    require(isinstance(dependencies, list), "Saved requiredDependencies must be an explicit list")
    if target["loader"] != "fabric":
        require(dependencies == [], "Unexpected required project on a non-Fabric file")
        return
    require(len(dependencies) == 1 and isinstance(dependencies[0], dict), "Exactly one required Fabric API project is expected")
    dependency = dependencies[0]
    require(dependency.get("dependencyType", "required") == "required", "Fabric API relation is not Required")
    api = target.get("fabricApi")
    require(isinstance(api, dict), "Current Fabric API pin is missing")
    if platform == "modrinth":
        require(api.get("modrinthProjectId") == "P7dR8mSH" and dependency.get("projectId") == "P7dR8mSH",
                "Wrong required Modrinth Fabric API project")
        require(dependency.get("versionId") == api.get("modrinthVersionId") and bool(api.get("modrinthVersionId")),
                "Saved Modrinth Fabric API version is not the exact build pin")
    else:
        require(api.get("curseforgeProjectId") == 306612 and type(dependency.get("projectId")) is int
                and dependency["projectId"] == 306612, "Wrong required CurseForge Fabric API project")
        require(dependency.get("versionId") is None and dependency.get("version") is None,
                "CurseForge related projects do not provide a Fabric API version pin")


def verify_download(record, target, root):
    result = {"verified": False, "cacheRehashed": False, "reason": "Actual download verification is pending"}
    expected = {"downloadedSha256": target["sha256"], "downloadedBytes": target["bytes"]}
    for field, value in expected.items():
        if field in record:
            require(record[field] == value and (field != "downloadedBytes" or type(record[field]) is int),
                    field + " differs from the current JAR")
    if record.get("downloadUrl") is not None:
        https_url(record["downloadUrl"])
    if record.get("downloadVerifiedAt") is not None:
        timestamp(record["downloadVerifiedAt"], "downloadVerifiedAt")
    if not all(record.get(field) is not None for field in (*expected, "downloadUrl", "downloadVerifiedAt")):
        return result
    result.update({"verified": True, "reason": "Recorded actual-download digest, size, HTTPS URL and timestamp match the current JAR"})
    if record.get("downloadedArtifact") is not None:
        path = relative_path(root, record["downloadedArtifact"])
        if path.exists():
            require(path.is_file(), "Retained download path is not a file")
            require(path.stat().st_size == target["bytes"] and digest(path) == target["sha256"], "Retained downloaded file differs from the current JAR")
            result["cacheRehashed"] = True
        else:
            result["cacheNote"] = "Optional local download cache is absent; recorded hash/size/URL/time remain historical proof"
    return result


def verify_platform(platform, manifest, manifest_sha, ledger, root=ROOT, require_downloads=False):
    report = {"platform": platform, "success": False, "expectedCount": 67, "submittedCount": 0,
              "metadataVerifiedCount": 0, "downloadVerifiedCount": 0, "downloadCacheRehashedCount": 0,
              "moderationApprovalInferred": False, "observedStatuses": {}, "errors": [], "files": []}
    try:
        require(platform in PLATFORMS, "Unknown platform")
        targets = manifest_targets(manifest, root)
        require(isinstance(ledger, dict) and type(ledger.get("schemaVersion")) is int
                and ledger["schemaVersion"] == 1, "Submission ledger is missing or has an unsupported schema")
        require(ledger.get("renderscopeVersion") == "0.3.0" and ledger.get("platform") == platform, "Wrong ledger release/platform")
        require(ledger.get("manifestSha256") == manifest_sha, "Ledger is bound to a different release manifest")
        require(all(ledger.get("projectId") == target[platform]["projectId"] for target in targets.values()), "Wrong platform project")
        files = ledger.get("files")
        require(isinstance(files, list), "Ledger files must be an explicit list")
        seen, remote_ids, statuses = set(), set(), Counter()
        for record in files:
            if not isinstance(record, dict):
                report["errors"].append("Ledger file record must be an object")
                continue
            row = {"identity": record.get("identity"), "submitted": False, "metadataVerified": False,
                   "downloadVerified": False, "errors": []}
            report["files"].append(row)
            try:
                identity = record.get("identity")
                require(identity in targets and identity not in seen, "Duplicate or unknown ledger identity")
                seen.add(identity)
                target = targets[identity]
                for field, expected in {"loader": target["loader"], "mc": target["minecraft"], "sha256": target["sha256"],
                        "bytes": target["bytes"], "file": target["fileName"], "packagedArtifact": target["packagedArtifact"],
                        "expectedGameVersions": [target["minecraft"]], "expectedLoaders": [target["loader"]],
                        "javaRuntime": target["javaRuntime"], "expectedReleaseChannel": "release",
                        "expectedFabricApi": target.get("fabricApi")}.items():
                    require(record.get(field) == expected, "Submission intent changed: " + field)
                require(record.get("clientOnly") is True and type(record.get("bytes")) is int
                        and type(record.get("javaRuntime")) is int, "Intent must preserve client-only scope, byte size and Java runtime")
                timestamp(record.get("submissionStartedAt"), "submissionStartedAt")
                require(record.get("submissionState") in ("submitting", "submitted"), "Unknown submission state")
                remote_id = record.get("remoteId")
                if remote_id is not None:
                    require(isinstance(remote_id, str) and bool(remote_id.strip()) and remote_id == remote_id.strip()
                            and remote_id not in remote_ids, "Missing or duplicate string remote ID")
                    remote_ids.add(remote_id)
                if record["submissionState"] != "submitted":
                    row["pendingReason"] = "Final UI submission is pending or uncertain; inspect the existing remote attempt before retry"
                    continue
                require(remote_id is not None, "Submitted record is missing its remote ID")
                https_url(record.get("remoteUrl"), platform)
                require(isinstance(record.get("observedStatus"), str) and bool(record["observedStatus"].strip()), "Raw saved UI status is missing")
                statuses[record["observedStatus"]] += 1
                row.update({"submitted": True, "remoteId": remote_id, "remoteUrl": record["remoteUrl"], "observedStatus": record["observedStatus"]})
                if record.get("metadataVerified") is True:
                    require(record.get("metadataSource") == "post-save-ui", "Metadata must come from actual post-save UI readback")
                    timestamp(record.get("metadataVerifiedAt"), "metadataVerifiedAt")
                    observed = record.get("observedMetadata")
                    require(isinstance(observed, dict), "Saved metadata readback is missing")
                    require(observed.get("gameVersions") == [target["minecraft"]] and observed.get("loaders") == [target["loader"]], "Saved file must target exactly one correct Minecraft version and loader")
                    require(observed.get("clientOnly") is True and observed.get("releaseChannel") == "release", "Saved file is not Client-only Release")
                    require(observed.get("fileName") == target["fileName"], "Saved file has a different filename")
                    if platform == "modrinth":
                        require(observed.get("versionNumber") == target["versionNumber"], "Saved Modrinth version number differs")
                    else:
                        require(type(observed.get("javaRuntime")) is int and observed["javaRuntime"] == target["javaRuntime"], "Saved CurseForge Java runtime tag differs")
                    required_dependencies(platform, target, observed)
                    row["metadataVerified"] = True
                else:
                    row["metadataPendingReason"] = "Post-save metadata has not yet been verified"
                download = verify_download(record, target, root)
                row["downloadVerified"] = download["verified"]
                row["download"] = download
            except (ValueError, KeyError, TypeError, OSError) as error:
                row["errors"].append(str(error))
                report["errors"].append(str(row["identity"]) + ": " + str(error))
        report["missingIdentities"] = sorted(set(targets) - seen)
        report["submittedCount"] = sum(row["submitted"] for row in report["files"])
        report["metadataVerifiedCount"] = sum(row["metadataVerified"] for row in report["files"])
        report["downloadVerifiedCount"] = sum(row["downloadVerified"] for row in report["files"])
        report["downloadCacheRehashedCount"] = sum(row.get("download", {}).get("cacheRehashed", False) for row in report["files"])
        report["observedStatuses"] = dict(statuses)
        report["success"] = (not report["errors"] and not report["missingIdentities"] and len(files) == 67
                and report["submittedCount"] == 67 and report["metadataVerifiedCount"] == 67
                and (not require_downloads or report["downloadVerifiedCount"] == 67))
    except (ValueError, KeyError, TypeError, OSError) as error:
        report["errors"].append(str(error))
    report["requireDownloads"] = require_downloads
    report["meaning"] = ("Offline validation of recorded UI submission/readback and actual-download fingerprints. "
        "No network requests, uploads, file generation, approval or public visibility are performed or inferred. "
        "An absent optional download cache does not erase recorded historical fingerprints.")
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", nargs="+", choices=PLATFORMS, default=list(PLATFORMS))
    parser.add_argument("--manifest", type=Path, default=ROOT / BASE / "release-manifest.json")
    parser.add_argument("--require-downloads", action="store_true")
    parser.add_argument("--json", action="store_true", help="Print full reports to stdout; never writes verification files")
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
        manifest_sha = digest(args.manifest)
        reports = []
        for platform in args.platform:
            path = ROOT / BASE / (platform + "-release-verification.json")
            ledger = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None
            reports.append(verify_platform(platform, manifest, manifest_sha, ledger, require_downloads=args.require_downloads))
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + "\n")
    if args.json:
        print(json.dumps({"success": all(row["success"] for row in reports), "platforms": reports}, ensure_ascii=False, indent=2))
    else:
        for row in reports:
            print(f"{row['platform']}: submitted {row['submittedCount']}/67; post-save metadata {row['metadataVerifiedCount']}/67; "
                  f"recorded downloads {row['downloadVerifiedCount']}/67 (cache rehashed {row['downloadCacheRehashedCount']}); "
                  + ("PASS" if row["success"] else "PENDING OR FAILED"))
            print("Observed moderation text (no approval inferred): " + json.dumps(row["observedStatuses"], ensure_ascii=False))
            for error in row["errors"][:12]:
                print("  " + error)
    raise SystemExit(0 if all(row["success"] for row in reports) else 1)


if __name__ == "__main__":
    main()
