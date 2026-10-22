"""Offline result-validator tests. Synthetic files never enter published evidence."""
import importlib.util
import json
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("fabric_launcher", Path(__file__).with_name("test-fabric-client.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class ModernFabricLauncherTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="renderscope-modern-launcher-")
        self.root = Path(self.temporary.name)
        (self.root / "mods").mkdir()
        primary = self.root / "mods/main.jar"
        primary.write_bytes(b"isolated validator fixture")
        sha = launcher.vendor.digest(primary, "sha256")
        self.mods = [{"file": "main.jar", "sha256": sha}]
        self.args = SimpleNamespace(output=self.root, game_dir=self.root, loader="0.19.5", minecraft="26.3", modern_smoke=True, extra_mod_manifest=None)
        self.result = {"success": True, "startedAt": "2026-10-22T00:00:00Z", "loader": "Fabric", "loaderVersion": "0.19.5",
                       "minecraftVersion": "26.3", "environment": "installed-production", "harness": "modern-installed-v1",
                       "primaryJarSha256": sha, "artifactSha256": sha, "artifactBytes": primary.stat().st_size,
                       "primaryJarBytes": primary.stat().st_size, "geometryAvailable": False, "gpuTimingAvailable": False,
                       "captureSeconds": 5.1, "captureFrames": 600, "heatmapGizmoBoxes": 5, "extractionScopes": 30, "submissionScopes": 30,
                       "inspectorHudDrawPasses": 12, "screenshots": [f"frame-{i}.png" for i in range(13)]+["inspector-hidden.png"]}
        for field in ("actualPrimaryOriginVerified", "runtimeLoaderVerified", "entityAndBlockEntityExtractionAndSubmission", "f8NativeScreen",
                      "pauseNativeButton", "selectedGizmoBoxAndLabels", "realCrosshairInspector", "reportPrivacy", "disconnectClearsInstances",
                      "selectedInstanceOwnerDetails", "rendererClassCopy", "highlightNativeButton",
                      "actualInspectorHudDrawn", "f1HidesInspectorHud", "inspectorResumesAfterF1"):
            self.result[field] = True
        for name in self.result["screenshots"]:
            (self.root / name).write_bytes(b"offline screenshot placeholder")

    def tearDown(self):
        self.temporary.cleanup()

    def verify(self):
        (self.root / "smoke-result.json").write_text(json.dumps(self.result), encoding="utf-8")
        return launcher.verify_result(self.args, 0, self.mods)

    def test_full_modern_contract_is_accepted(self):
        self.assertTrue(self.verify()["success"])

    def test_success_flag_cannot_replace_ui_origin_or_privacy_assertions(self):
        for field in ("actualPrimaryOriginVerified", "f8NativeScreen", "rendererClassCopy", "reportPrivacy", "disconnectClearsInstances",
                      "actualInspectorHudDrawn", "f1HidesInspectorHud", "inspectorResumesAfterF1"):
            with self.subTest(field=field):
                self.result[field] = False
                with self.assertRaises(RuntimeError):
                    self.verify()
                self.result[field] = True

    def test_old_development_scope_is_never_installed_evidence(self):
        self.result["environment"] = "fabric-development-client"
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_data_inspection_without_real_hud_passes_is_not_a_rendered_inspector(self):
        self.result["inspectorHudDrawPasses"] = 0
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_missing_screenshot_and_duplicate_names_are_rejected(self):
        (self.root / self.result["screenshots"][-1]).unlink()
        with self.assertRaises(RuntimeError):
            self.verify()
        self.result["screenshots"][-1] = self.result["screenshots"][0]
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_artifact_bytes_and_unavailable_geometry_are_verified(self):
        self.result["artifactBytes"] += 1
        with self.assertRaises(RuntimeError):
            self.verify()
        self.result["artifactBytes"] -= 1
        self.result["geometryAvailable"] = True
        with self.assertRaises(RuntimeError):
            self.verify()

    def test_default_legacy_path_does_not_acquire_modern_requirements(self):
        self.args.modern_smoke = False
        self.result = {key: self.result[key] for key in ("success", "startedAt", "loader", "loaderVersion", "minecraftVersion", "environment", "primaryJarSha256")}
        self.assertTrue(self.verify()["success"])

    def test_explicit_api_pin_allows_the_exact_beta_and_default_still_selects_release(self):
        def version(name, kind):
            return {"version_number": name, "version_type": kind, "id": name, "files": [{"primary": True, "url": "https://example.invalid/api.jar", "filename": name + ".jar", "hashes": {"sha1": "0" * 40}}]}
        data = [version("beta-exact", "beta"), version("release-default", "release")]
        with patch.object(launcher, "json_url", return_value=data), patch.object(launcher.vendor, "fetch", side_effect=lambda url, path, sha: path):
            self.assertEqual("beta-exact", launcher.api_mod("26.3", self.root, "beta-exact")[1]["version"])
            self.assertEqual("release-default", launcher.api_mod("26.3", self.root)[1]["version"])
            with self.assertRaises(RuntimeError):
                launcher.api_mod("26.3", self.root, "absent-pin")


if __name__ == "__main__":
    unittest.main()
