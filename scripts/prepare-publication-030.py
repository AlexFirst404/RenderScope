"""Prepare local 0.3.0 publication material from audited binaries and real evidence.

No network writes, Gradle invocation or platform upload is performed. Refresh after
the package index and gameplay evidence change; publicationReady is deliberately
false until every exact target has matching world results, manual visual evidence
and a packaged copy.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path, PureWindowsPath
import re
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DESTINATION = ROOT / "distribution/platforms/0.3.0"
REPO_URL = "https://github.com/AlexFirst404/RenderScope"
RAW_URL = "https://raw.githubusercontent.com/AlexFirst404/RenderScope/main/"
SOURCE_REF = "v0.3.0"
LOADER_NAMES = {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}
API_PROJECT = "P7dR8mSH"
API_URL = f"https://api.modrinth.com/v2/project/{API_PROJECT}/version"
VISUAL_MANIFESTS = (
    "distribution/validation/root-world-visual-review.json",
    "mc21/validation/world-visual-review.json",
    "loader21/validation/world-visual-review.json",
    "modern/validation/world-visual-review.json",
)
NEOFORGE_BLOCK_ENTITY_SELECTOR_TARGETS = {
    "1.21.9": "21.9.16-beta", "1.21.10": "21.10.64", "1.21.11": "21.11.48",
    "26.1": "26.1.0.19-beta", "26.1.1": "26.1.1.15-beta", "26.1.2": "26.1.2.122",
    "26.2": "26.2.0.93", "26.3": "26.3.0.95",
}


def module(name: str, relative: str):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def write(relative: str, text: str) -> None:
    text = text.replace(REPO_URL + "/blob/main/", REPO_URL + "/blob/" + SOURCE_REF + "/")
    text = text.replace(RAW_URL, "https://raw.githubusercontent.com/AlexFirst404/RenderScope/" + SOURCE_REF + "/")
    path = DESTINATION / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def fabric_pins() -> dict[str, str]:
    pins = {"1.20.1": "0.92.12+1.20.1"}
    if "fabric-api:" + pins["1.20.1"] not in (ROOT / "fabric/build.gradle").read_text(encoding="utf-8"):
        raise RuntimeError("The inspected Fabric 1.20.1 API pin changed")
    for relative in ("mc20/build.gradle", "mc21/build.gradle", "modern/build.gradle", "modern/mapped/build.gradle"):
        text = (ROOT / relative).read_text(encoding="utf-8")
        block = re.search(r"def (?:apiVersions|apis)\s*=\s*\[(.*?)\]", text, re.S)
        if not block:
            raise RuntimeError("Fabric API map is missing from " + relative)
        pins.update(re.findall(r"'([^']+)'\s*:\s*'([^']+)'", block[1]))
    return pins


def api_dependencies(pins: dict[str, str], cache: Path) -> list[dict]:
    if not cache.is_file():
        request = urllib.request.Request(API_URL, headers={"User-Agent": f"RenderScope-publication-preparation/0.3.0 ({REPO_URL})"})
        with urllib.request.urlopen(request, timeout=45) as response:
            data = response.read()
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
    versions = json.loads(cache.read_text(encoding="utf-8"))
    rows = []
    for minecraft, pin in pins.items():
        matches = [version for version in versions if version["version_number"] == pin
                   and version["project_id"] == API_PROJECT and "fabric" in version["loaders"]
                   and minecraft in version["game_versions"]]
        if len(matches) != 1:
            raise RuntimeError(f"Exact official Fabric API version is ambiguous or missing: {pin}")
        version = matches[0]
        file = next(file for file in version["files"] if file["primary"])
        rows.append({"minecraft": minecraft, "version": pin,
            "releaseType": version["version_type"],
            "maven": "net.fabricmc.fabric-api:fabric-api:" + pin,
            "modrinthProjectId": API_PROJECT, "modrinthVersionId": version["id"],
            "modrinthVersionUrl": f"https://modrinth.com/mod/fabric-api/version/{version['id']}",
            "curseforgeProjectId": 306612, "curseforgeSlug": "fabric-api",
            "curseforgeProjectSource": "https://www.curseforge.com/minecraft/mc-mods/fabric-api",
            "file": file["filename"], "fileUrl": file["url"], "sha512": file["hashes"]["sha512"],
            "source": f"https://api.modrinth.com/v2/version/{version['id']}"})
    return rows


def ownership_regressions(target: dict) -> dict:
    core_cases = {"sharedImplementationKeepsIndependentOwnerRowsAndModDetailLookup",
                  "rollingExpiryRemovesOnlyTheExpiredOwnersRendererAndIncludesZeroFrames",
                  "capturePreservesBothOwnersAfterRollingExpiryAndIncludesEmptyFrames"}
    replacement_cases = {"sampleContextQualifiesLegacyBlockKeysAndLiveLookupUsesTheSameIdentity",
                         "rollingInstancesAndDiagnosticDrilldownKeepEachActualOwnersCosts",
                         "capturePreservesReplacedHistoryWhileRollingExpiryAndZeroFramesRemainCorrect",
                         "differentTypesAtOnePositionInTheSameFrameKeepTheirObjectAndModGroups"}
    modern = target["project"].startswith(("modern", "loader-mapped", "loader26"))
    exporter = "dev.renderscope.modern.ModernReportJsonTest" if modern else "dev.renderscope.client.report.ReportExporterTest"
    export_case = ("sharedRendererExportPreservesBothRegistryOwnersWithoutChangingClassIds" if modern
                   else "sharedRendererExportPreservesSeparateOwnerRowsAndUnchangedClassIds")
    replacement_export_case = ("replacedBlockTypesKeepIndependentCaptureCostsWithoutRawInstanceKeys" if modern
                               else "replacedBlockTypesKeepSeparateCapturedInstancesWithoutExposingTheirKeys")
    proofs = []

    def failed(reason: str) -> dict:
        return {"passed": False, "reason": reason, "suites": proofs,
                "blockEntityReplacement": {"passed": False, "reason": reason}}

    suites = (("dev.renderscope.core.RendererOwnershipTest", core_cases, ["renderer-owner-isolation"]),
              ("dev.renderscope.core.BlockEntityReplacementTest", replacement_cases, ["block-entity-replacement"]),
              (exporter, {export_case, replacement_export_case}, ["renderer-owner-export", "block-entity-replacement-export"]))
    for suite_name, expected, covers in suites:
        path = ROOT / target["testResults"] / ("TEST-" + suite_name + ".xml")
        if not path.is_file():
            return failed("Missing current regression suite: " + suite_name)
        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        names = {case.attrib.get("name", "").removesuffix("()") for case in cases}
        if (suite.attrib.get("name") != suite_name or int(suite.attrib.get("tests", 0)) != len(cases)
                or any(int(suite.attrib.get(key, 0)) != 0 for key in ("failures", "errors", "skipped"))
                or not expected.issubset(names)
                or any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped"))):
            return failed("Required owner/replacement/rolling/capture/export cases did not all pass: " + suite_name)
        proofs.append({"path": path.relative_to(ROOT).as_posix(),
                       "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                       "tests": len(cases), "requiredPassedCases": sorted(expected), "covers": covers})
    return {"passed": True, "suites": proofs, "blockEntityReplacement": {"passed": True,
            "suite": proofs[1]["path"], "exportSuite": proofs[2]["path"],
            "requiredPassedCases": sorted(replacement_cases), "requiredExporterCase": replacement_export_case}}


def repository_file(relative: str) -> Path:
    if not isinstance(relative, str) or Path(relative).is_absolute() or PureWindowsPath(relative).drive:
        raise ValueError("Expected a repository-relative evidence path")
    path = (ROOT / relative).resolve()
    if not path.is_relative_to(ROOT.resolve()) or not path.is_file():
        raise ValueError("Evidence file is missing or escapes the repository: " + relative)
    return path


def load_visual_reviews() -> list[dict]:
    """Normalize only the four explicit, existing manual-review schemas."""
    reviews = []
    for relative in VISUAL_MANIFESTS:
        path = ROOT / relative
        if not path.is_file():
            continue
        try:
            document = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            continue
        if not isinstance(document, dict) or document.get("schemaVersion") != 1:
            continue
        fingerprint = hashlib.sha256(path.read_bytes()).hexdigest()
        records = document.get("targets", document.get("profiles", []))
        if isinstance(records, dict):
            # mc21's established manifest explicitly describes Fabric targets.
            if relative != "mc21/validation/world-visual-review.json":
                continue
            records = [{**row, "minecraft": minecraft, "loader": "fabric"}
                       for minecraft, row in records.items() if isinstance(row, dict)]
        if not isinstance(records, list):
            continue
        for original in records:
            if not isinstance(original, dict):
                continue
            row = dict(original)
            # Explicit aliases in the existing mc21 schema, never prose inference.
            if relative == "mc21/validation/world-visual-review.json":
                row.setdefault("worldBoxesAligned", row.get("boundingBoxesVisibleAndAligned"))
                row.setdefault("inspectorVisible", row.get("inspectorAndTrackingVisible"))
            row.setdefault("smokeResultSha256", row.get("resultSha256"))
            row.setdefault("resultFile", row.get("result"))
            row.setdefault("reviewedAt", document.get("reviewedAt"))
            row["manifestFile"] = relative
            row["manifestSha256"] = fingerprint
            reviews.append(row)
    return reviews


def modern_inspector_result_verified(minecraft: str, result: dict) -> bool:
    modern = minecraft in ("1.21.9", "1.21.10", "1.21.11") or minecraft.startswith("26.")
    return (modern and all(result.get(flag) is True for flag in (
        "actualInspectorHudDrawn", "f1HidesInspectorHud", "inspectorResumesAfterF1"))
        and type(result.get("inspectorHudDrawPasses")) is int and result["inspectorHudDrawPasses"] > 0)


def manual_visual_review(target: dict, primary_sha: str, evidence: list[dict], reviews: list[dict]) -> dict:
    """Require one complete review of the same current, accepted runtime result.

    Several optional profiles may share an artifact. A review of one profile
    cannot approve a different result, image set, loader, or Minecraft identity.
    """
    rejected = []
    matching = [row for row in reviews if row.get("minecraft") == target["minecraft"]
                and isinstance(row.get("loader"), str) and row["loader"].lower() == target["loader"]]
    for row in matching:
        try:
            if row.get("primaryJarSha256") != primary_sha:
                raise ValueError("Manual review belongs to a different primary JAR")
            if not all(row.get(key) is True for key in ("worldBoxesAligned", "labelsReadable", "inspectorVisible")):
                raise ValueError("Aligned boxes, readable labels and visible inspector must all be explicitly true")
            if not isinstance(row.get("reviewedAt"), str):
                raise ValueError("Manual review time is missing")
            datetime.fromisoformat(row["reviewedAt"].replace("Z", "+00:00"))
            manifest = repository_file(row["manifestFile"])
            if hashlib.sha256(manifest.read_bytes()).hexdigest() != row["manifestSha256"]:
                raise ValueError("Manual review manifest changed after it was loaded")
            linked = None
            for candidate in evidence:
                path = repository_file(candidate["path"])
                if hashlib.sha256(path.read_bytes()).hexdigest() != candidate["sha256"]:
                    continue
                record = json.loads(path.read_text(encoding="utf-8"))
                if not isinstance(record, dict):
                    continue
                if record.get("resultFile"):
                    if (record.get("success") is not True or record.get("minecraftVersion") != target["minecraft"]
                            or str(record.get("loader", "")).lower() != target["loader"]
                            or record.get("loaderVersion") != target["loaderVersion"]
                            or record.get("primaryJarSha256") != primary_sha):
                        continue
                    name = record["resultFile"]
                    if not isinstance(name, str) or Path(name).name != name or "\\" in name:
                        continue
                    result_path = repository_file((path.parent / name).relative_to(ROOT.resolve()).as_posix())
                    result_sha = hashlib.sha256(result_path.read_bytes()).hexdigest()
                    if record.get("resultSha256") != result_sha:
                        continue
                else:
                    result_path, result_sha = path, candidate["sha256"]
                if result_sha != row.get("smokeResultSha256"):
                    continue
                if row.get("resultFile") and repository_file(row["resultFile"]) != result_path:
                    continue
                result = json.loads(result_path.read_text(encoding="utf-8"))
                if not isinstance(result, dict):
                    continue
                if (result.get("success") is not True or result.get("minecraftVersion") != target["minecraft"]
                        or str(result.get("loader", "")).lower() != target["loader"]
                        or result.get("loaderVersion") != target["loaderVersion"]
                        or result.get("primaryJarSha256") != primary_sha
                        or result.get("environment") != candidate["environment"]):
                    continue
                linked = (result_path, result_sha, result)
                break
            if linked is None:
                raise ValueError("Manual review is not linked to this exact accepted runtime result")
            result_path, result_sha, result = linked
            if (target["minecraft"] in ("1.21.9", "1.21.10", "1.21.11") or target["minecraft"].startswith("26.")):
                if not modern_inspector_result_verified(target["minecraft"], result):
                    raise ValueError("Modern Inspector requires actual HUD draws, F1 hiding and restoration in the same runtime result")
            allowed_images = set()
            for name in result.get("screenshots", []):
                if not isinstance(name, str) or Path(name).is_absolute() or PureWindowsPath(name).is_absolute():
                    continue
                for image in (result_path.parent / name, result_path.parent / "gallery" / name):
                    image = image.resolve()
                    if image.is_relative_to(result_path.parent) and image.is_file():
                        allowed_images.add(image)
            declared = row.get("screenshots")
            if declared is None and isinstance(row.get("screenshotsSha256"), dict):
                declared = []
                for name, sha in row["screenshotsSha256"].items():
                    choices = [image for image in allowed_images if image.name == name
                               and hashlib.sha256(image.read_bytes()).hexdigest() == sha]
                    if len(choices) != 1:
                        raise ValueError("Reviewed screenshot name/hash is missing or ambiguous")
                    declared.append({"file": choices[0].relative_to(ROOT.resolve()).as_posix(), "sha256": sha})
            if not isinstance(declared, list) or len(declared) < 2:
                raise ValueError("A world/selection image and inspector image must both be manually reviewed")
            images = []
            for image in declared:
                path = repository_file(image["file"])
                if path not in allowed_images or hashlib.sha256(path.read_bytes()).hexdigest() != image["sha256"]:
                    raise ValueError("Reviewed image differs from the exact runtime screenshot")
                images.append({"file": path.relative_to(ROOT.resolve()).as_posix(), "sha256": image["sha256"]})
            names = {Path(image["file"]).name.lower() for image in images}
            if (not any("inspector" in name for name in names)
                    or not any("heatmap" in name or "selected" in name for name in names)):
                raise ValueError("Reviewed screenshots do not cover both world selection/heatmap and inspector")
            return {"passed": True, "manifestFile": row["manifestFile"], "manifestSha256": row["manifestSha256"],
                    "reviewedAt": row["reviewedAt"], "primaryJarSha256": primary_sha,
                    "resultFile": result_path.relative_to(ROOT.resolve()).as_posix(), "resultSha256": result_sha,
                    "worldBoxesAligned": True, "labelsReadable": True, "inspectorVisible": True,
                    "screenshots": images}
        except (ValueError, KeyError, TypeError, OSError, json.JSONDecodeError) as error:
            rejected.append({"manifestFile": row.get("manifestFile"), "reason": str(error)})
    return {"passed": False, "reason": "No complete current-hash manual visual review matches accepted runtime evidence",
            "rejectedCandidates": rejected}


def modern_inspector_fix_verified(target: dict, artifact: Path, visual: dict) -> bool:
    if not visual.get("passed"):
        return False
    try:
        result_path = repository_file(visual["resultFile"])
        if hashlib.sha256(result_path.read_bytes()).hexdigest() != visual["resultSha256"]:
            return False
        result = json.loads(result_path.read_text(encoding="utf-8"))
        return (result.get("success") is True and result.get("minecraftVersion") == target["minecraft"]
                and str(result.get("loader", "")).lower() == target["loader"]
                and result.get("loaderVersion") == target["loaderVersion"]
                and result.get("primaryJarSha256") == visual["primaryJarSha256"]
                == hashlib.sha256(artifact.read_bytes()).hexdigest()
                and modern_inspector_result_verified(target["minecraft"], result))
    except (ValueError, KeyError, TypeError, OSError):
        return False


def neoforge_block_entity_selector_verified(target: dict, artifact: Path, visual: dict) -> bool:
    if (target["loader"] != "neoforge"
            or target["minecraft"] not in NEOFORGE_BLOCK_ENTITY_SELECTOR_TARGETS
            or NEOFORGE_BLOCK_ENTITY_SELECTOR_TARGETS.get(target["minecraft"]) != target.get("loaderVersion")
            or not modern_inspector_fix_verified(target, artifact, visual)):
        return False
    try:
        result = json.loads(repository_file(visual["resultFile"]).read_text(encoding="utf-8"))
        fields = ("entityExtractionCalls", "entitySubmissionCalls", "blockEntityExtractionCalls", "blockEntitySubmissionCalls")
        if not all(type(result.get(field)) is int and result[field] > 0 for field in fields):
            return False
        boolean = "Z" if target["minecraft"] in ("26.2", "26.3") else ""
        selector = ("tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
                    "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;" + boolean
                    + "Lnet/minecraft/client/renderer/culling/Frustum;)"
                    "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;")
        with zipfile.ZipFile(artifact) as jar:
            return selector.encode("ascii") in jar.read("dev/renderscope/modern/mixin/BlockEntityDispatcherMixin.class")
    except (ValueError, KeyError, TypeError, OSError, zipfile.BadZipFile):
        return False


def target_release_fixes(target: dict, artifact: Path, visual: dict) -> tuple[list[str], list[str]]:
    en, ru = [], []
    if not visual["passed"]:
        return en, ru
    if target["minecraft"] == "1.21.6":
        with zipfile.ZipFile(artifact) as jar:
            data = jar.read("dev/renderscope/client/world/WorldOverlay.class")
        # The inspected target-only adapter adds the boolean foreground pass.
        if b"WorldOverlay$LabelPlacement;Z)V" in data:
            en.append("Fixed Minecraft 1.21.6 world-label depth: text stays readable above its background.")
            ru.append("Исправлена глубина мировых меток Minecraft 1.21.6: текст остаётся читаемым поверх фона.")
    if target["loader"] in ("forge", "neoforge") and target["minecraft"] in ("1.21.9", "1.21.10", "1.21.11"):
        en.append("Corrected the native resource-pack metadata for this Minecraft version; verified through an installed-client world.")
        ru.append("Исправлены нативные метаданные resource pack для этой версии Minecraft; проверено в мире установленного клиента.")
    if modern_inspector_fix_verified(target, artifact, visual):
        if target["loader"] == "forge":
            en.append("Fixed a missing Inspector HUD on Forge's early-return render path. F1 hides and restores the overlay; verified actual HUD draw counts and framebuffer frames.")
            ru.append("Исправлено исчезновение Inspector HUD при раннем выходе из отрисовки Forge. F1 скрывает и возвращает оверлей; проверены реальные вызовы HUD и кадры игры.")
        else:
            en.append("Inspector HUD visibility and F1 hide/restore are verified using actual HUD draw counts and framebuffer frames.")
            ru.append("Видимость Inspector HUD и скрытие/возврат через F1 проверены по реальным вызовам HUD и кадрам игры.")
    if neoforge_block_entity_selector_verified(target, artifact, visual):
        en.append("Fixed NeoForge block-entity extraction profiling by targeting its concrete Frustum overload; verified positive entity and block-entity extraction/submission calls in installed Minecraft.")
        ru.append("Исправлено профилирование извлечения состояния блок-сущностей NeoForge: выбрана нужная перегрузка с Frustum; в установленном Minecraft проверены положительные вызовы extraction/submission для сущностей и блок-сущностей.")
    return en, ru


def publication_ready(rows: list[dict], git_identity: dict) -> bool:
    return (len(rows) == 67 and len({(row["loader"], row["minecraft"]) for row in rows}) == 67
            and git_identity.get("passed") is True
            and all(row.get("worldVerified") is True and row.get("packageIndexMatches") is True
                    and row.get("rendererOwnerIsolationPresent") is True
                    and row.get("rendererOwnerRegressions", {}).get("passed") is True
                    and row.get("rendererOwnerRegressions", {}).get("blockEntityReplacement", {}).get("passed") is True
                    and row.get("visuallyVerified") is True
                    and (row["minecraft"] not in ("1.21.9", "1.21.10", "1.21.11")
                         and not row["minecraft"].startswith("26.")
                         or row.get("modernInspectorHudFrameValidationVerified") is True)
                    and row.get("manualVisualReview", {}).get("passed") is True
                    and row["manualVisualReview"].get("primaryJarSha256") == row["sha256"]
                    for row in rows))


def git_evidence_files(rows: list[dict], media: list[dict]) -> list[dict]:
    """Published hash-bearing inputs; ignored Gradle XML is checked separately."""
    files = {}

    def add(relative: str, expected: str, role: str) -> None:
        path = (ROOT / relative).resolve()
        if not path.is_relative_to(ROOT.resolve()):
            raise RuntimeError("Evidence path escapes repository: " + relative)
        relative = path.relative_to(ROOT.resolve()).as_posix()
        previous = files.get(relative)
        if previous and previous["sha256"] != expected:
            raise RuntimeError("Conflicting evidence fingerprints: " + relative)
        files[relative] = {"path": relative, "sha256": expected, "role": role}

    for row in rows:
        for evidence in row["runtimeEvidence"]:
            add(evidence["path"], evidence["sha256"], "runtime evidence")
            path = ROOT / evidence["path"]
            record = json.loads(path.read_text(encoding="utf-8"))
            if record.get("resultFile"):
                result = (path.parent / record["resultFile"]).relative_to(ROOT).as_posix()
                add(result, record["resultSha256"], "linked runtime result")
        visual = row.get("manualVisualReview", {})
        if visual.get("passed"):
            add(visual["manifestFile"], visual["manifestSha256"], "manual visual review")
            add(visual["resultFile"], visual["resultSha256"], "visually reviewed runtime result")
            for screenshot in visual["screenshots"]:
                add(screenshot["file"], screenshot["sha256"], "manually reviewed screenshot")
    for asset in media:
        add(asset["path"], asset["sha256"], "publication media")
    return sorted(files.values(), key=lambda record: record["path"])


def git_evidence_identity(files: list[dict], reference: str = "index") -> dict:
    """Compare raw bytes to Git objects, without applying checkout/clean filters."""
    def git(*arguments: str) -> bytes:
        return subprocess.run(["git", *arguments], cwd=ROOT, check=True,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

    algorithm = git("rev-parse", "--show-object-format").decode("ascii").strip()
    if algorithm not in ("sha1", "sha256"):
        raise RuntimeError("Unsupported Git object format: " + algorithm)
    index = reference == "index"
    listing = git("ls-files", "--stage", "-z") if index else git("ls-tree", "-r", "-z", "--full-tree", reference)
    blobs = {}
    for entry in listing.split(b"\0"):
        if not entry:
            continue
        metadata, name = entry.split(b"\t", 1)
        mode, second, third = metadata.decode("ascii").split()
        if (index and third != "0") or (not index and second != "blob"):
            continue
        blobs[name.decode("utf-8")] = second if index else third
    checked = []
    for record in files:
        path = ROOT / record["path"]
        raw = path.read_bytes() if path.is_file() else None
        raw_sha = hashlib.sha256(raw).hexdigest() if raw is not None else None
        raw_blob = (hashlib.new(algorithm, b"blob " + str(len(raw)).encode("ascii") + b"\0" + raw).hexdigest()
                    if raw is not None else None)
        stored = blobs.get(record["path"])
        passed = raw is not None and raw_sha == record["sha256"] and raw_blob == stored
        checked.append({**record, "passed": passed, "rawSha256": raw_sha,
                        "rawGitBlob": raw_blob, "storedGitBlob": stored,
                        "reason": None if passed else ("missing file" if raw is None else
                            "raw bytes differ from declared fingerprint" if raw_sha != record["sha256"] else
                            "not present in Git reference" if stored is None else
                            "Git bytes differ from raw file; stage as-produced bytes without text conversion")})
    return {"passed": bool(checked) and all(record["passed"] for record in checked),
            "reference": reference, "objectFormat": algorithm, "files": checked}


def verify_prepared_git_evidence(reference: str) -> bool:
    manifest = DESTINATION / "release-manifest.json"
    summary = json.loads(manifest.read_text(encoding="utf-8"))
    media_path = DESTINATION / "media-manifest.json"
    media = json.loads(media_path.read_text(encoding="utf-8"))["assets"]
    files = git_evidence_files(summary["targets"], media)
    for path in (manifest, media_path, DESTINATION / "fabric-api-dependencies.json"):
        files.append({"path": path.relative_to(ROOT).as_posix(),
                      "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "role": "prepared publication metadata"})
    report = git_evidence_identity(files, reference)
    failed = [record for record in report["files"] if not record["passed"]]
    print(f"Git/raw evidence byte identity against {reference}: {len(files) - len(failed)}/{len(files)} matched.")
    for record in failed:
        print(record["path"] + ": " + record["reason"])
    return report["passed"]


def limitations(minecraft: str) -> tuple[str, str]:
    if minecraft == "1.20.1":
        return ("Entity/block-entity renderer CPU intervals. Version-gated optional integrations include supported Flywheel visual CPU tasks and attributed Mekanism deferred callbacks. Observed immediate-buffer geometry is partial. Shared Flywheel GPU timestamps, when available in Full, stay separate from objects and are not exported as capture GPU timings. Optional-mod support requires its own pinned combination evidence.",
                "CPU-интервалы рендереров сущностей и блок-сущностей. Версионные интеграции включают поддерживаемые visual CPU-задачи Flywheel и отложенные вызовы Mekanism. Геометрия стандартных буферов наблюдается частично. Общие GPU-метки Flywheel, если доступны в Full, отделены от объектов и не экспортируются как GPU-тайминги capture. Совместимость модов подтверждается отдельно для точного сочетания версий.")
    if minecraft in ("1.21.9", "1.21.10", "1.21.11") or minecraft.startswith("26."):
        return ("Exclusive CPU render-state extraction and submission, grouped by source. Later deferred feature batches, geometry and GPU timings are unavailable per object. Zero geometry fields do not mean an object emits no geometry. Terrain, shader execution and shared instancing work are not assigned to objects.",
                "Раздельные эксклюзивные CPU-интервалы извлечения render state и submission. Поздние отложенные feature batches, геометрия и GPU-тайминги отдельных объектов недоступны. Нулевые поля геометрии не означают отсутствия геометрии. Terrain, выполнение шейдеров и общий instancing не распределяются между объектами.")
    return ("Version-specific entity/block-entity CPU hooks, including extraction where available. Geometry is limited to observed standard buffers; newer Sodium/Iris native geometry is unavailable. GPU work, terrain, shader execution and shared instancing are not assigned to objects. A vanilla baseline does not establish optional-mod or shader compatibility.",
            "Версионные CPU-хуки сущностей и блок-сущностей, включая extraction там, где доступен. Геометрия ограничена наблюдаемыми стандартными буферами; native-геометрия новых Sodium/Iris недоступна. GPU, terrain, выполнение шейдеров и общий instancing не распределяются между объектами. Ванильный прогон не подтверждает совместимость со всеми модами и шейдерами.")


def gallery(rows: list[dict]) -> list[dict]:
    brand = ROOT / "distribution/brand/renderscope-logo-384x384.png"
    assets = [{"kind": "brand", "path": brand.relative_to(ROOT).as_posix(),
               "sha256": hashlib.sha256(brand.read_bytes()).hexdigest(), "title": "RenderScope"}]
    slots = [("mods", "Measured mod table / Измеренная таблица модов"),
             ("heatmap", "World heatmap / Теплокарта в мире"),
             ("selected", "A selected real instance / Выбранный экземпляр мира")]
    for keyword, title in slots:
        selected = None
        for row in rows:
            if not row["rendererOwnerIsolationPresent"]:
                continue
            for evidence in row["runtimeEvidence"]:
                record_path = ROOT / evidence["path"]
                record = json.loads(record_path.read_text(encoding="utf-8"))
                details_path = record_path.parent / record["resultFile"] if record.get("resultFile") else record_path
                details = json.loads(details_path.read_text(encoding="utf-8"))
                for name in details.get("screenshots", []):
                    if not isinstance(name, str) or keyword not in name.lower():
                        continue
                    image = (details_path.parent / name).resolve()
                    if not image.is_relative_to(details_path.parent.resolve()) or not image.is_file():
                        continue
                    selected = {"kind": "current-hash gameplay", "path": image.relative_to(ROOT).as_posix(),
                        "sha256": hashlib.sha256(image.read_bytes()).hexdigest(), "title": title,
                        "minecraft": row["minecraft"], "loader": row["loader"], "artifactSha256": row["sha256"],
                        "runtimeScope": evidence["environment"], "runtimeEvidence": evidence["path"],
                        "runtimeEvidenceSha256": evidence["sha256"]}
                    break
                if selected:
                    break
            if selected:
                break
        if selected:
            assets.append(selected)
    return assets


def archive_media() -> list[dict]:
    """Reuse real, previously verified footage without presenting it as 0.3.0 QA."""
    path = ROOT / "distribution/forge/presentation/presentation-manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    selected = {"create-shaft-live.gif", "create-press-live.gif", "heatmap.png"}
    assets = []
    for entry in manifest["animations"] + manifest["stills"]:
        if entry["file"] not in selected:
            continue
        asset = path.parent / entry["file"]
        if (hashlib.sha256(asset.read_bytes()).hexdigest() != entry["sha256"]
                or not entry["transparentCornersVerified"] or not entry["originalUnchangedVerified"]):
            raise RuntimeError("Archived illustration differs from its recorded source: " + entry["file"])
        record = {"kind": "archived gameplay illustration", "path": asset.relative_to(ROOT).as_posix(),
            "sha256": entry["sha256"], "renderscopeVersion": "0.2.0", "minecraft": "1.20.1",
            "loader": "forge", "loaderVersion": "47.4.10", "runtimeScope": "installed-production",
            "artifactSha256": manifest["primaryJarSha256"],
            "sourceManifest": path.relative_to(ROOT).as_posix(),
            "sourceManifestSha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "meaning": "Archived feature illustration only; not current 0.3.0 runtime evidence",
            "cornerRadius": entry["cornerRadius"]}
        if entry.get("sourceCapture"):
            capture = ROOT / entry["sourceCapture"]
            raw = capture.read_bytes()
            details = json.loads(raw)
            if (hashlib.sha256(raw).hexdigest() != entry["sourceCaptureSha256"]
                    or details["primaryJarSha256"] != manifest["primaryJarSha256"]
                    or details["environment"] != "installed-production" or entry["frameCount"] != 16):
                raise RuntimeError("Archived footage provenance is inconsistent: " + entry["file"])
            record.update({key: entry[key] for key in ("sourceCapture", "sourceCaptureSha256", "frameCount", "durationMillis")})
        assets.append(record)
    if {Path(asset["path"]).name for asset in assets} != selected:
        raise RuntimeError("The agreed archived rounded illustrations are incomplete")
    return assets


def footage_section(language: str) -> str:
    en = language == "en"
    ru = language == "ru"
    heading = "Real footage · archive" if en else "Настоящая запись · архив" if ru else "Real footage / Настоящая запись · archive 0.2.0"
    intro_en = ("**Archive: RenderScope 0.2.0 · Minecraft 1.20.1 · Forge 47.4.10.** "
                "These actual installed-client recordings show Create 6.0.8 / Flywheel 1.0.5 with the indirect backend and Mekanism 10.4.16.80. "
                "They illustrate the workflow, not current 0.3.0 validation or compatibility on other targets. "
                "The GIFs use 16 real captured frames each; rounded corners do not alter the displayed measurements.")
    intro_ru = ("**Архив: RenderScope 0.2.0 · Minecraft 1.20.1 · Forge 47.4.10.** "
                "Запись настоящего установленного клиента: Create 6.0.8 / Flywheel 1.0.5 с indirect backend и Mekanism 10.4.16.80. "
                "Это иллюстрация работы, а не текущая проверка 0.3.0 или совместимости других версий. "
                "В каждом GIF 16 реально снятых кадров; скруглены только внешние углы, показанные измерения не изменены.")
    text = "\n## " + heading + "\n\n" + (intro_en if en else intro_ru if ru else intro_en + "\n\n" + intro_ru)
    for name, caption_en, caption_ru in (
        ("create-shaft-live.gif", "Selected rotating Create shaft · archived 0.2.0", "Выбранный вращающийся вал Create · архив 0.2.0"),
        ("create-press-live.gif", "Selected moving Create press · archived 0.2.0", "Выбранный работающий пресс Create · архив 0.2.0"),
        ("heatmap.png", "Relative measured object CPU heatmap · archived 0.2.0", "Теплокарта относительной измеренной CPU-стоимости · архив 0.2.0")):
        caption = caption_en if en else caption_ru if ru else caption_en + " / " + caption_ru
        text += f"\n\n![{caption}]({RAW_URL}distribution/forge/presentation/{name})\n\n*{caption}.*"
    label = "Recorded provenance" if en else "Происхождение записи" if ru else "Recorded provenance / Происхождение записи"
    return text + f"\n\n[{label}]({REPO_URL}/blob/main/distribution/forge/presentation/presentation-manifest.json).\n"


def evidence_links(media: list[dict], language: str) -> str:
    heading = ("Current 0.3.0 evidence" if language == "en" else "Доказательства текущей 0.3.0" if language == "ru"
               else "Current 0.3.0 evidence / Доказательства текущей 0.3.0")
    lines = ["\n## " + heading, ""]
    for asset in media:
        if asset["kind"] != "current-hash gameplay":
            continue
        labels = asset["title"].split(" / ", 1)
        title = labels[0] if language == "en" else labels[-1] if language == "ru" else asset["title"]
        lines.append(f"- [{title}]({RAW_URL}{asset['path']}) — {asset['minecraft']} / {LOADER_NAMES[asset['loader']]} / "
                     f"{asset['runtimeScope']}. [JSON]({REPO_URL}/blob/main/{asset['runtimeEvidence']}).")
    return "\n".join(lines) + "\n"


HEADER = """# RenderScope 0.3.0

![RenderScope pixel-art logo](https://raw.githubusercontent.com/AlexFirst404/RenderScope/main/distribution/brand/renderscope-logo-384x384.png)

"""

EN = """## English

**Find a measured render cost. Follow it to one object in your world.**

RenderScope connects object-render CPU work to a mod, a type and a specific entity or block entity. It provides **67 separate client builds for 23 stable Minecraft releases, 1.20.1–26.3**, with Fabric, Forge and NeoForge adapters. The server does not need the mod.

### Install

1. Choose **one JAR matching your exact Minecraft version and loader**. Do not install builds for different loaders together. Forge has no upstream release for 1.20.5 or 1.21.2.
2. Use **Java 25 for Minecraft 26.x**, or **Java 21 for earlier RenderScope targets**.
3. For Fabric, also install the matching **Fabric API**. Use the [exact loader and API table](https://github.com/AlexFirst404/RenderScope/blob/main/distribution/platforms/0.3.0/DEPENDENCIES.md); pinned upstream beta versions remain identified.
4. Put the JAR in your client profile's `mods` folder. Enter a world and press **F8**, or open **Esc → RenderScope**. F8 can be rebound in Controls.

### Find the object

1. Read the first page's largest **measured object CPU** contributor. Early results are marked preliminary.
2. Open **Mods → Objects**, select a type, then inspect an instance. Registry ownership and renderer implementation ownership stay visible separately.
3. Use **Highlight** to see its box, **Track** to keep it marked, **Heatmap** to compare nearby measured objects, or **Inspect** for the object under your crosshair.

In **1.20.1–1.21.8**, **Track** also shows a direction and distance HUD, and the UI offers **Find object / Show in world**. In **1.21.9–26.3**, **Track** keeps the selected object marked in the world; the UI offers **Show the instance / Inspect measured mods**. English and Russian are included.

### Metrics and recording

- Read FPS/frame-time percentiles, measured CPU averages/P95/peaks, calls and the live timeline.
- Record **5 / 10 / 30 seconds** or stop manually, then export JSON to the profile's `renderscope-reports` folder.
- **Timing Only** records supported CPU intervals. **Full** adds only extended metrics the adapter can really observe.

CPU time is **not GPU time or a promised FPS improvement from removing a mod**. Parallel visual CPU intervals may overlap. Terrain, particles, GUI, shader execution and unobserved shared/deferred work are outside per-object totals.

For **1.21.9–26.3**, object metrics measure render-state extraction/submission CPU; per-object geometry and GPU timings are unavailable. A zero geometry field does not prove that the object emits no geometry.

**1.20.1** retains version-gated Flywheel visual CPU and Mekanism deferred integrations. Supported shared Flywheel GPU timestamps are asynchronous, separate from objects and live-only; capture exports CPU data. Check [exact optional-mod and shader evidence](https://github.com/AlexFirst404/RenderScope/blob/main/docs/OPTIONAL-RENDERER-COMPATIBILITY.md); one tested combination does not establish support for every version.

### Privacy

Reports stay local; nothing is automatically uploaded. **Positions and dimensions are off by default.** Player names, server addresses, world names, chat, UUIDs and absolute paths are excluded. Installed mod names/versions and measured CPU data are included.

[Build and world evidence](https://github.com/AlexFirst404/RenderScope/blob/main/docs/compatibility/SUPPORT-MATRIX.md) records exact JAR hashes and distinguishes installed production clients from development clients. A build pass alone is not a gameplay or shader compatibility pass.

### Source and license

**RenderScope Source-Available License 1.0** allows personal/private tool use, modification and free redistribution/inclusion under its terms. Selling the mod/code, including it in paid projects/modpacks/bundles, or charging for access requires prior written permission. [Source and full license](https://github.com/AlexFirst404/RenderScope/blob/main/LICENSE).
"""

RU = """## Русский

**Найдите измеренную стоимость рендера — и конкретный объект в мире.**

RenderScope связывает CPU-работу отрисовки с модом, типом и отдельной сущностью или блок-сущностью. Подготовлены **67 отдельных клиентских сборок для 23 стабильных версий Minecraft, от 1.20.1 до 26.3**: Fabric, Forge и NeoForge. На сервер мод не нужен.

### Установка

1. Выберите **один JAR строго под свою версию Minecraft и загрузчик**. Не устанавливайте сборки для разных загрузчиков вместе. У Forge нет upstream-релизов для 1.20.5 и 1.21.2.
2. Для **Minecraft 26.x нужна Java 25**, для предыдущих целей RenderScope — **Java 21**.
3. Для Fabric дополнительно нужен подходящий **Fabric API**. Версии указаны в [точной таблице загрузчиков и API](https://github.com/AlexFirst404/RenderScope/blob/main/distribution/platforms/0.3.0/DEPENDENCIES.md); beta-статус upstream-сборок сохранён.
4. Положите JAR в клиентскую папку `mods`. Зайдите в мир и нажмите **F8** или **Esc → RenderScope**. Клавишу можно сменить в настройках управления.

### Как найти объект

1. На первой странице посмотрите лидера **измеренной CPU-работы объектов**. Ранние результаты помечены как предварительные.
2. Откройте **Mods → Objects**, выберите тип и затем экземпляр. Владелец registry-объекта и владелец реализации renderer показаны отдельно.
3. **Highlight** подсветит объект, **Track** сохранит его отметку, **Heatmap** сравнит ближайшие измеренные объекты, а **Inspect** — объект под прицелом.

В **1.20.1–1.21.8** режим **Track** также показывает направление и расстояние, а UI предлагает **Найти объект / Показать в мире**. В **1.21.9–26.3** режим **Track** сохраняет отметку выбранного объекта в мире; UI предлагает **Показать объект / Изучить моды**. Интерфейс доступен на русском и английском.

### Метрики и запись

- Смотрите FPS/процентили времени кадра, среднее/P95/пик измеренной CPU-работы, вызовы и живой timeline.
- Запишите **5 / 10 / 30 секунд** или остановите запись вручную, затем экспортируйте JSON в `renderscope-reports` внутри профиля.
- **Timing Only** измеряет поддерживаемые CPU-интервалы. **Full** добавляет только реально наблюдаемые адаптером расширенные метрики.

CPU-время **не является GPU-временем или обещанием прироста FPS после удаления мода**. Параллельные visual CPU-интервалы могут пересекаться. Terrain, particles, GUI, выполнение шейдеров и ненаблюдаемая общая/deferred-работа не входят в суммы отдельных объектов.

В **1.21.9–26.3** измеряется CPU extraction/submission render state; геометрия и GPU-тайминги отдельных объектов недоступны. Нулевое поле геометрии не доказывает, что объект не создаёт геометрию.

В **1.20.1** сохранены версионные интеграции visual CPU-задач Flywheel и отложенных вызовов Mekanism. Поддерживаемые общие GPU-метки Flywheel асинхронны, отделены от объектов и доступны только в live UI; capture экспортирует CPU-данные. Смотрите [точные сочетания модов и шейдеров](https://github.com/AlexFirst404/RenderScope/blob/main/docs/OPTIONAL-RENDERER-COMPATIBILITY.md): один успешный тест не означает поддержку всех версий.

### Приватность

Отчёты остаются локально, автоматической отправки нет. **Координаты и dimension по умолчанию выключены.** Имена игроков, адреса серверов, имена миров, чат, UUID и абсолютные пути исключены. В отчёт входят названия/версии установленных модов и измеренные CPU-данные.

[Матрица сборок и миров](https://github.com/AlexFirst404/RenderScope/blob/main/docs/compatibility/SUPPORT-MATRIX.md) связывает доказательства с точным хешем JAR и различает установленный клиент и dev-среду. Успешная сборка не считается игровым или shader-тестом.

### Исходники и лицензия

**RenderScope Source-Available License 1.0** разрешает личное/приватное использование как инструмента, изменения и бесплатное включение/распространение по её условиям. Продажа, включение кода/мода в платные проекты/модпаки/пакеты и платный доступ требуют предварительного письменного разрешения. [Исходники и полная лицензия](https://github.com/AlexFirst404/RenderScope/blob/main/LICENSE).
"""


def main() -> None:
    global SOURCE_REF
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--channel", choices=("alpha", "beta", "release"), default="release")
    parser.add_argument("--api-cache", type=Path, default=ROOT / ".tools/fabric-api-official-versions.json")
    parser.add_argument("--source-ref", default="v0.3.0", help="Public release tag/ref used by prepared text links")
    parser.add_argument("--verify-git-evidence", action="store_true",
                        help="Only verify existing metadata/evidence raw bytes against Git; no file writes")
    parser.add_argument("--git-ref", default="index", help="Git reference for --verify-git-evidence: index, HEAD or release tag")
    args = parser.parse_args()
    if args.verify_git_evidence:
        raise SystemExit(0 if verify_prepared_git_evidence(args.git_ref) else 1)
    if not re.fullmatch(r"[A-Za-z0-9._/-]+", args.source_ref):
        raise RuntimeError("Unsafe source ref")
    SOURCE_REF = args.source_ref
    builder = module("publication_build", "scripts/build-all.py")
    support = module("publication_support", "scripts/generate-support-matrix.py")
    targets = builder.targets()
    if len(targets) != 67 or len({target["minecraft"] for target in targets}) != 23:
        raise RuntimeError("Expected exactly 67 loader targets across 23 stable Minecraft releases")
    pins = fabric_pins()
    if set(pins) != {target["minecraft"] for target in targets if target["loader"] == "fabric"}:
        raise RuntimeError("Fabric API pins do not cover the exact release set")
    dependencies = api_dependencies(pins, args.api_cache)
    by_api = {row["minecraft"]: row for row in dependencies}
    package_index = json.loads((ROOT / "distribution/0.3.0/artifacts.json").read_text(encoding="utf-8"))
    packages = {(row["minecraft"], row["loader"]): row for row in package_index["artifacts"]}
    visual_reviews = load_visual_reviews()
    rows = []
    for target in targets:
        proof = builder.audit(target)
        source = ROOT / proof["artifact"]
        with zipfile.ZipFile(source) as jar:
            owner_isolation = "dev/renderscope/core/RenderProfiler$RendererKey.class" in jar.namelist()
        package = packages.get((target["minecraft"], target["loader"]), {})
        packaged = ROOT / package.get("packagedArtifact", "missing")
        synchronized = (package.get("sha256") == proof["sha256"] and package.get("bytes") == proof["bytes"]
                        and packaged.is_file() and builder.digest(packaged) == proof["sha256"])
        evidence = support.inspect_runtime(target, proof["sha256"])
        visual = manual_visual_review(target, proof["sha256"], evidence, visual_reviews)
        modern_hud_verified = modern_inspector_fix_verified(target, source, visual)
        neo_be_verified = neoforge_block_entity_selector_verified(target, source, visual)
        fixes_en, fixes_ru = target_release_fixes(target, source, visual)
        regressions = ownership_regressions(target)
        en, ru = limitations(target["minecraft"])
        dependency = by_api.get(target["minecraft"]) if target["loader"] == "fabric" else None
        changelog = f"changelogs/{target['loader']}-{target['minecraft']}.md"
        status_en = "Current hash-matched world evidence: " + ", ".join(sorted({row["environment"] for row in evidence})) if evidence else "World verification is pending for this exact artifact hash."
        status_ru = "Для текущего хеша есть игровой результат: " + ", ".join(sorted({row["environment"] for row in evidence})) if evidence else "Игровая проверка именно этого хеша ещё не завершена."
        java = 25 if target["minecraft"].startswith("26.") else 21
        write(changelog, f"## RenderScope 0.3.0 — {LOADER_NAMES[target['loader']]} / Minecraft {target['minecraft']}\n\n"
              f"- Client only; {target['loader']} {target['loaderVersion']}, Java {java}. No server installation.\n"
              + (f"- Required Fabric API: **{dependency['version']}**.\n" if dependency else "")
              + "- Clearer first-page diagnostics; drill down to a measured instance and highlight it in the world.\n"
              "- F8/Pause UI, heatmap, crosshair inspector, timeline, capture and privacy-safe local JSON.\n"
              "- Shared renderer classes retain separate registry-owner rows; CPU attribution is not a causal FPS prediction.\n\n"
              "- Replacing a block with another type at the same position keeps separate instance/capture costs; Inspector checks the live registry type.\n\n"
              + "".join("- " + fix + "\n" for fix in fixes_en)
              + ("\n" if fixes_en else "")
              + f"Measurement scope: {en}\n\nValidation: **{proof['unitTests']} unit tests**; {status_en}\n"
              + "\n".join(f"- [Evidence]({REPO_URL}/blob/main/{row['path']})" for row in evidence)
              + f"\n\nSHA-256: `{proof['sha256']}` ({proof['bytes']} bytes).\n\n---\n\n## Русский\n\n"
              f"Клиентская сборка для **Minecraft {target['minecraft']} / {target['loader']} {target['loaderVersion']} / Java {java}**. На сервер мод не нужен.\n\n"
              + (f"Обязателен Fabric API **{dependency['version']}**.\n\n" if dependency else "")
              + "Первая страница объясняет измерения и ведёт к конкретному экземпляру. Сохранены F8/Pause, таблицы, подсветка, теплокарта, инспектор, timeline, capture и приватный JSON. Общий renderer-класс учитывается отдельно для каждого registry owner. При замене блока другим типом в той же позиции стоимость экземпляров и история capture разделены; Inspector проверяет текущий registry-тип. CPU-доли не обещают прирост FPS.\n\n"
              + "".join("- " + fix + "\n" for fix in fixes_ru)
              + ("\n" if fixes_ru else "")
              + f"Область измерения: {ru}\n\nПроверено unit-тестов: **{proof['unitTests']}**. {status_ru}\n\n"
              + f"RenderScope Source-Available License 1.0: изменения и бесплатные проекты разрешены по условиям лицензии; продажи, платные содержащие проекты/модпаки и платный доступ требуют письменного разрешения. [Полная лицензия]({REPO_URL}/blob/main/LICENSE).")
        rows.append({**proof, "fileName": source.name, "sha1": hashlib.sha1(source.read_bytes()).hexdigest(),
            "sha512": hashlib.sha512(source.read_bytes()).hexdigest(), "versionNumber": "0.3.0+mc" + target["minecraft"] + "-" + target["loader"],
            "displayName": f"RenderScope 0.3.0 · {LOADER_NAMES[target['loader']]} {target['minecraft']}",
            "javaRuntime": java, "environment": "client", "clientSide": "required", "serverSide": "unsupported",
            "releaseChannel": args.channel, "packagedArtifact": package.get("packagedArtifact"),
            "packageIndexMatches": synchronized, "rendererOwnerIsolationPresent": owner_isolation,
            "rendererOwnerRegressions": regressions,
            "runtimeEvidence": evidence, "worldVerified": bool(evidence), "manualVisualReview": visual,
            "visuallyVerified": visual["passed"], "verifiedTargetFixes": {"en": fixes_en, "ru": fixes_ru},
            "modernInspectorHudFrameValidationVerified": modern_hud_verified,
            "neoforgeBlockEntitySelectorVerified": neo_be_verified,
            "changelog": "distribution/platforms/0.3.0/" + changelog,
            "measurementScope": {"en": en, "ru": ru}, "fabricApi": dependency,
            "modrinth": {"projectId": "LyIIxCef", "gameVersions": [target["minecraft"]], "loaders": [target["loader"]],
                "versionType": args.channel, "dependencies": [] if not dependency else [{"project_id": API_PROJECT,
                    "version_id": dependency["modrinthVersionId"], "dependency_type": "required"}]},
            "curseforge": {"projectId": 1756581, "gameVersions": [target["minecraft"]],
                "loaders": [target["loader"]], "environmentScope": "file", "environment": ["Client"],
                "javaVersions": [java], "releaseType": args.channel,
                "requiredProjects": [] if not dependency else [{"projectId": 306612, "slug": "fabric-api", "relation": "requiredDependency"}]},
            "remoteState": "Not queried by local preparation; canonical platform status is recorded separately"})
    world_count = sum(row["worldVerified"] for row in rows)
    visual_count = sum(row["visuallyVerified"] for row in rows)
    modern_hud_count = sum(row["modernInspectorHudFrameValidationVerified"] for row in rows)
    neo_be_count = sum(row["neoforgeBlockEntitySelectorVerified"] for row in rows)
    package_count = sum(row["packageIndexMatches"] for row in rows)
    regression_count = sum(row["rendererOwnerRegressions"]["passed"] for row in rows)
    replacement_count = sum(row["rendererOwnerRegressions"]["blockEntityReplacement"]["passed"] for row in rows)
    media = gallery(rows) + archive_media()
    git_identity = git_evidence_identity(git_evidence_files(rows, media))
    ready = publication_ready(rows, git_identity)
    summary = {"schemaVersion": 1, "preparedAt": datetime.now(timezone.utc).isoformat(), "renderscopeVersion": "0.3.0",
        "mode": "local preparation only; no platform writes", "publicReleaseAuthorized": True,
        "publicationReady": ready, "publicationPerformed": False, "releaseChannel": args.channel,
        "minecraftReleaseCount": 23, "artifactCount": 67, "worldVerifiedTargets": world_count,
        "manuallyVisuallyVerifiedTargets": visual_count,
        "modernInspectorHudFrameValidationVerifiedTargets": modern_hud_count,
        "neoforgeBlockEntitySelectorVerifiedTargets": neo_be_count,
        "packageIndexMatchedTargets": package_count,
        "rendererOwnerRegressionVerifiedTargets": regression_count,
        "blockEntityReplacementRegressionVerifiedTargets": replacement_count,
        "gitEvidenceByteIdentity": git_identity,
        "finalGitByteVerification": "After staging generated metadata, run python scripts/prepare-publication-030.py --verify-git-evidence; use --git-ref v0.3.0 after tagging to check the actual public source reference.",
        "remoteStatusAuthority": "distribution/platforms/publication-status.json",
        "runtimeMeaning": "Installed production and development-client evidence remain distinct. Every accepted result matches this exact primary JAR hash. Each target also needs an explicit manual review of aligned boxes, readable labels and visible Inspector, linked to the same accepted result and exact screenshot bytes. A vanilla result is not optional-mod or shader evidence.",
        "license": "LicenseRef-RenderScope-Source-Available-1.0", "sourceUrl": REPO_URL, "sourceRef": SOURCE_REF,
        "licenseUrl": REPO_URL + "/blob/main/LICENSE", "targets": rows}
    write("release-manifest.json", json.dumps(summary, ensure_ascii=False, indent=2))
    write("fabric-api-dependencies.json", json.dumps({"source": API_URL,
        "sourceCacheSha256": hashlib.sha256(args.api_cache.read_bytes()).hexdigest(),
        "meaning": "Exact build-pinned required Fabric API versions; not latest-version guesses.", "targets": dependencies}, indent=2))
    table = ["# Exact release dependencies / Точные зависимости", "",
        "Install one matching client JAR. Fabric requires the build-pinned API below; game validation is recorded separately. Forge/NeoForge runtime integrations are bundled or supplied by the pinned loader. No RenderScope server component is required.", "",
        "Установите один подходящий клиентский JAR. Для Fabric ниже указан точный проверяемый Fabric API; версия загрузчика и версия игры выбираются отдельно.", "",
        "| Minecraft | Fabric Loader | Fabric API | Forge | NeoForge | Java |", "| --- | --- | --- | --- | --- | --- |"]
    for mc in sorted(pins, key=lambda value: tuple(map(int, value.split(".")))):
        selected = {row["loader"]: row for row in rows if row["minecraft"] == mc}
        api = by_api[mc]
        api_status = "" if api["releaseType"] == "release" else " · " + api["releaseType"]
        table.append(f"| {mc} | {selected['fabric']['loaderVersion']} | [{api['version']}]({api['modrinthVersionUrl']}){api_status} | "
                     + selected.get("forge", {}).get("loaderVersion", "Upstream unavailable") + " | "
                     + selected["neoforge"]["loaderVersion"] + f" | {selected['fabric']['javaRuntime']} |")
    write("DEPENDENCIES.md", "\n".join(table))
    write("media-manifest.json", json.dumps({"meaning": "Original pixel-art branding, explicitly archived 0.2.0 rounded feature illustrations, and linked raw current-hash 0.3.0 QA PNGs. Archived footage is not current runtime evidence. No gameplay or metrics were generated.", "assets": media}, ensure_ascii=False, indent=2))
    write("DESCRIPTION_EN.md", HEADER + EN + footage_section("en") + evidence_links(media, "en"))
    write("DESCRIPTION_RU.md", HEADER + RU + footage_section("ru") + evidence_links(media, "ru"))
    for platform in ("modrinth", "curseforge"):
        write(platform + "-description-030.ready.md", HEADER + EN + "\n---\n\n" + RU
              + "\n---\n" + footage_section("both") + evidence_links(media, "both"))
    write("MEDIA_LAYOUT.md", "# 0.3.0 platform description media layout\n\n"
        "1. Unchanged original pixel-art logo enlarged by nearest-neighbour; no old version-specific cover.\n"
        "2. Explicit English and Русский sections, each with Install / Find object / Metrics / Privacy / License.\n"
        "3. Separate bilingual archive block: rounded Create shaft GIF, rounded Create press GIF, rounded heatmap PNG. "
        "Each caption identifies RenderScope 0.2.0 / Forge 47.4.10 / Minecraft 1.20.1; the text names Create 6.0.8, Flywheel 1.0.5 indirect and Mekanism 10.4.16.80. "
        "Both GIFs contain 16 actual framebuffer frames / 2.4 seconds. Their source JAR is "
        "`1de007b2ae41e514cb19f358e7a7923910f30b2b9dff9f04e3797521a4264b0a`; these are illustrations, not 0.3.0 proof.\n"
        "4. Current-hash 0.3.0 raw QA PNGs appear as evidence links with Minecraft / loader / scope / JSON, not as silently restyled screenshots.\n\n"
        "No source screenshot, measurement pixel, animation frame or logo was modified by this preparation. "
        "Archived presentation PNGs already have an alpha-only rounded corner mask; their RGB data is unchanged. "
        "Archived GIFs already have rounded transparency and palette encoding, with source-frame provenance. "
        "The historical [presentation manifest](../../forge/presentation/presentation-manifest.json) and capture records remain intact. "
        "Product descriptions contain no preparation status or DRAFT text; readiness stays in the separate release manifest.\n")
    validation = (f"Current snapshot: {world_count}/67 exact artifact hashes have real-world evidence; "
                  f"{visual_count}/67 have matching manual world/Inspector visual reviews; "
                  f"{package_count}/67 packaged copies match the current audited binaries. "
                  f"{regression_count}/67 targets have passed the actual owner-isolation, block-replacement, rolling, capture and exporter XML regressions. "
                  f"Git/raw byte identity for hashed evidence and media: {'passed' if git_identity['passed'] else 'pending staging or mismatched'}. "
                  "Installed production and development-client scopes are listed per artifact. "
                  + ("All exact targets satisfy the local release gate. Platform publication and moderation status are recorded separately."
                     if ready else "DRAFT: this preparation is not a public upload, moderation approval or an assertion that all worlds have passed."))
    changelog = ("# RenderScope 0.3.0\n\n"
        "- 67 separate client builds for 23 stable Minecraft releases, 1.20.1–26.3.\n"
        "- Fabric, Forge and NeoForge adapters use their real version-specific rendering APIs.\n"
        "- Clearer diagnostics connect measured CPU work to a mod, type and concrete world instance.\n"
        "- Shared renderer implementations retain independent registry-owner attribution.\n"
        "- Replaced block types at one position retain separate instance/capture costs; Inspector checks the live registry type.\n"
        "- Native UI, highlight/track, heatmap, Inspector, timeline, capture and private JSON.\n"
        "- Explicit partial measurement scope: unavailable geometry/GPU work is never invented.\n\n"
        + ("- Fixed Minecraft 1.21.6 world-label depth with a version-specific foreground pass.\n\n"
           if sum(bool(row["verifiedTargetFixes"]["en"]) for row in rows if row["minecraft"] == "1.21.6") == 3 else "")
        + ("- Corrected Forge/NeoForge 1.21.9–1.21.11 resource-pack metadata; installed-client worlds verified.\n\n"
           if sum(row["visuallyVerified"] for row in rows if row["minecraft"] in ("1.21.9", "1.21.10", "1.21.11") and row["loader"] != "fabric") == 6 else "")
        + ("- Fixed Inspector HUD on Forge early-return paths; actual HUD visibility and F1 hide/restore verified across all 24 Minecraft 1.21.9–26.3 builds.\n\n"
           if modern_hud_count == 24 else "")
        + ("- Corrected NeoForge block-entity extraction hooks for its Frustum overload; all eight exact Minecraft 1.21.9–26.3 targets verified with real entity/block-entity calls.\n\n"
           if neo_be_count == 8 else "")
        + validation + "\n\n[Exact dependencies](DEPENDENCIES.md) · [Per-artifact evidence](release-manifest.json)\n\n"
        "## Русский\n\n67 отдельных клиентских сборок для 23 стабильных версий Minecraft. "
        "Диагностика ведёт от измеренной CPU-работы к конкретному объекту; общие renderer-классы учитываются отдельно для каждого registry owner. "
        "Замена блока другим типом в той же позиции сохраняет раздельную стоимость экземпляров и историю capture; Inspector проверяет текущий registry-тип. "
        "Сохранены нативный UI, подсветка, tracking, теплокарта, инспектор, timeline, capture и приватный JSON. "
        "Недоступные GPU/geometry-метрики не подменяются оценками.\n\n"
        + ("Исправлена глубина мировых меток Minecraft 1.21.6 отдельным проходом переднего текста.\n\n"
           if sum(bool(row["verifiedTargetFixes"]["ru"]) for row in rows if row["minecraft"] == "1.21.6") == 3 else "")
        + ("Исправлены resource-pack метаданные Forge/NeoForge 1.21.9–1.21.11; проверены миры установленных клиентов.\n\n"
           if sum(row["visuallyVerified"] for row in rows if row["minecraft"] in ("1.21.9", "1.21.10", "1.21.11") and row["loader"] != "fabric") == 6 else "")
        + ("Исправлен Inspector HUD при раннем выходе из отрисовки Forge; реальная видимость HUD и скрытие/возврат через F1 проверены на всех 24 сборках Minecraft 1.21.9–26.3.\n\n"
           if modern_hud_count == 24 else "")
        + ("Исправлены хуки извлечения состояния блок-сущностей NeoForge для перегрузки с Frustum; на всех восьми точных целях Minecraft 1.21.9–26.3 проверены реальные вызовы сущностей и блок-сущностей.\n\n"
           if neo_be_count == 8 else "")
        + f"Для текущих хешей есть {world_count}/67 игровых результатов, {visual_count}/67 ручных визуальных проверок; совпадает {package_count}/67 упакованных JAR. "
        "Установленный клиент и dev-среда различаются в доказательствах. "
        + ("Все цели прошли локальную проверку готовности. Фактическая публикация и модерация платформ учитываются отдельно.\n\n"
           if ready else "ЧЕРНОВИК: это локальная подготовка, а не подтверждение публикации или одобрения платформами.\n\n")
        + f"[Полная лицензия]({REPO_URL}/blob/main/LICENSE): бесплатные проекты и изменения разрешены по её условиям; платные содержащие проекты, продажи и платный доступ требуют письменного разрешения.")
    write("CHANGELOG_0.3.0.md", changelog)
    write("GITHUB_RELEASE_0.3.0.md", changelog + f"\n\n[All exact downloads]({REPO_URL}/blob/main/distribution/0.3.0/README.md)")
    write("README.md", "# RenderScope 0.3.0 publication preparation\n\n" + validation
        + "\n\nRegenerate after package/runtime updates: `python scripts/prepare-publication-030.py` "
        "(default channel Release as requested; `--source-ref v0.3.0` pins final evidence links to the release tag). "
        "This helper never uploads, modifies a platform project, runs Gradle or starts Minecraft. "
        "The existing 0.2.0 saved descriptions, proofs and publication status are historical records and are not overwritten.\n\n"
        "The JSON manifest contains 67 file-level publication records, exact required Fabric API Modrinth version IDs / CurseForge project relations, "
        "Java requirements, legal identity, changelog paths, hashes and scope-specific evidence. "
        "`publicationReady` stays false until all 67 world results and package hashes match and actual XML confirms "
        "the three core ownership/rolling/capture cases, all four block-entity replacement cases, and both matching exporter regressions without failures/errors/skips. "
        "Hashed runtime evidence and media must also match raw Git index blobs, without line-ending conversion. "
        "All 67 identities additionally require a manual review with explicit aligned-box/readable-label/visible-inspector approval, "
        "the current primary JAR SHA-256, the same accepted runtime-result SHA-256, and the actual world/selection plus Inspector PNG paths and hashes. "
        "A missing, failed, stale, mixed-profile or changed-image review keeps the gate false. "
        "For Minecraft 1.21.9–26.3, the same result must also confirm actual Inspector HUD draws, F1 hiding/restoration and a positive draw counter. "
        "After staging the generated metadata, run `python scripts/prepare-publication-030.py --verify-git-evidence`; "
        "after tagging, repeat with `--git-ref v0.3.0` to check the exact public reference. "
        "Generated remoteState is not a claim about uploaded files; actual platform state remains in the separate canonical status record.\n")
    print(f"Prepared {len(rows)} exact targets / {len(dependencies)} pinned Fabric API versions; "
          f"worlds {world_count}/67, packages {package_count}/67, Git/raw evidence={git_identity['passed']}, "
          f"manual visuals {visual_count}/67, "
          f"publicationReady={ready}. No external writes.")


if __name__ == "__main__":
    main()
