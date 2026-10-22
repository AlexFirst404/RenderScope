#!/usr/bin/env python3
"""Verify and collect the native loader21/older installed world evidence."""
import datetime
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    targets = read(ROOT / "loader21/validation/world-targets.json")["targets"]
    builds = {(t["loader"], t["minecraft"]): t for t in
              read(ROOT / "loader21/validation/native-build-matrix.json")["targets"]}
    reviews = {(t["loader"], t["minecraft"]): t for t in
               read(ROOT / "loader21/validation/world-visual-review.json")["targets"]}
    rows = []
    expected_checks = ["runtimeLoaderVerified", "realEntityAndBlockEntitySamples",
                       "fullModeGeometryAndRenderTypes", "reportLoaderMetadataVerified",
                       "captureExportPrivacy", "timingOnlyFallback", "f8AndControlsRegistration",
                       "pauseMenuButton", "highlightAndTrackState", "crosshairTargetsRealBlockEntity"]
    for target in targets:
        key = (target["loader"], target["minecraft"])
        build = builds[key]
        out = ROOT / "distribution" / key[0] / key[1] / "installed"
        result_path = out / "smoke-result.json"
        result = read(result_path)
        verification = read(out / "installed-verification.json")
        review = reviews[key]
        assert result["success"] is True and verification["success"] is True, key
        assert result["environment"] == "installed-production", key
        assert result["minecraftVersion"] == key[1], key
        assert result["loader"].lower() == key[0], key
        assert result["loaderVersion"] == target["loaderVersion"], key
        main_sha = digest(ROOT / target["artifact"])
        assert main_sha == build["sha256"] == result["primaryJarSha256"] == verification["primaryJarSha256"] == review["primaryJarSha256"], key
        result_sha = digest(result_path)
        assert result_sha == verification["resultSha256"] == review["smokeResultSha256"], key
        assert verification["resultFile"] == "smoke-result.json", key
        assert build["unitTests"] >= 66, key
        assert all(result.get(check) is True for check in expected_checks), key
        assert result["worldOverlayBoxesSubmitted"] > 0 and result["worldOverlayLabelsSubmitted"] > 0, key
        assert result["captureDurationSeconds"] >= 5 and result["captureFrames"] > 0, key
        assert len(result["screenshots"]) == 28, key
        start = datetime.datetime.fromisoformat(result["startedAt"].replace("Z", "+00:00")).timestamp()
        screenshots = []
        for name in result["screenshots"]:
            image = out / "gallery" / name
            assert image.stat().st_mtime >= start, (key, name)
            screenshots.append({"file": image.relative_to(ROOT).as_posix(), "sha256": digest(image)})
        assert all(review[check] is True for check in ["worldBoxesAligned", "labelsReadable", "inspectorVisible"]), key
        for image in review["screenshots"]:
            assert digest(ROOT / image["file"]) == image["sha256"], key
        rows.append({"minecraft": key[1], "loader": key[0], "loaderVersion": target["loaderVersion"],
                     "success": True, "environment": result["environment"], "artifact": target["artifact"],
                     "primaryJarSha256": main_sha, "unitTests": build["unitTests"],
                     "resultFile": result_path.relative_to(ROOT).as_posix(), "resultSha256": result_sha,
                     "verificationFile": (out / "installed-verification.json").relative_to(ROOT).as_posix(),
                     "startedAt": result["startedAt"], "completedAt": result["completedAt"],
                     "captureFrames": result["captureFrames"], "captureDurationSeconds": result["captureDurationSeconds"],
                     "checks": {check: result[check] for check in expected_checks},
                     "worldOverlayBoxesSubmitted": result["worldOverlayBoxesSubmitted"],
                     "worldOverlayLabelsSubmitted": result["worldOverlayLabelsSubmitted"],
                     "visualReview": {"reviewedAt": review["reviewedAt"], "worldBoxesAligned": True,
                                      "labelsReadable": True, "inspectorVisible": True},
                     "screenshots": screenshots})
    document = {"schemaVersion": 1, "verifiedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "scope": "Actual installed production JARs in isolated official Forge/NeoForge client profiles; manually reviewed heatmap and inspector screenshots.",
                "success": True, "targetCount": len(rows), "screenshotCount": sum(len(r["screenshots"]) for r in rows),
                "targets": rows}
    output = ROOT / "loader21/validation/installed-world-results.json"
    output.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(rows)} installed worlds and {document['screenshotCount']} actual screenshots: {output.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
