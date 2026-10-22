"""Offline wiring regressions; temporary fixtures never become gameplay evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[1]


def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, REPO / relative)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


support = load("modern_runtime_support", "scripts/generate-support-matrix.py")
preparer = load("modern_runtime_publication", "scripts/prepare-publication-030.py")
publication_support = preparer.module("modern_runtime_publication_support", "scripts/generate-support-matrix.py")
runner = load("modern_runtime_runner", "modern/run-installed-worlds.py")
verifier = load("modern_runtime_verifier", "modern/verify-build.py")
TARGETS = json.loads((REPO / "modern/targets.json").read_text(encoding="utf-8"))["targets"]


class ModernRuntimeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="renderscope-runtime-wiring-")
        self.root = Path(self.temporary.name)
        self.old_roots = [module.ROOT for module in (support, publication_support, runner)]
        for module in (support, publication_support, runner):
            module.ROOT = self.root
        self.old_repo = verifier.REPO
        verifier.REPO = self.root
        self.sha = "a" * 64

    def tearDown(self):
        for module, root in zip((support, publication_support, runner), self.old_roots):
            module.ROOT = root
        verifier.REPO = self.old_repo
        self.temporary.cleanup()

    def write(self, relative, data):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes((json.dumps(data) + "\n").encode("utf-8"))
        return path

    def identity(self, target, environment):
        return {"success": True, "minecraftVersion": target["minecraft"], "loader": "Fabric",
                "loaderVersion": target["loaderVersion"], "environment": environment,
                "primaryJarSha256": self.sha, "artifactSha256": self.sha}

    def installed(self, target, change=None):
        relative = target["runtimeEvidence"][0]
        detail = self.identity(target, "installed-production")
        if change:
            change(detail)
        child = self.write(str(Path(relative).parent / "smoke-result.json"), detail)
        wrapper = self.identity(target, "installed-production")
        wrapper.update(resultFile=child.name, resultSha256=support.builder.digest(child))
        self.write(relative, wrapper)
        return relative

    def both_readers(self, target):
        results = [module.inspect_runtime(target, self.sha) for module in (support, publication_support)]
        self.assertEqual(results[0], results[1], "Support and publication must use the same proof contract")
        return results[0]

    def complete_runtime(self, target, installed=True, change=None):
        runtime = self.identity(target, "installed-production" if installed else "fabric-development-client")
        runtime.update(artifactBytes=123, primaryJarBytes=123, captureSeconds=5.1, captureFrames=600,
                       heatmapGizmoBoxes=5, extractionScopes=30, submissionScopes=30,
                       harness="modern-installed-v1", geometryAvailable=False, gpuTimingAvailable=False,
                       screenshots=[f"world-{i}.png" for i in range(13)])
        for field in ("entityAndBlockEntityExtractionAndSubmission", "f8NativeScreen", "pauseNativeButton",
                      "selectedGizmoBoxAndLabels", "realCrosshairInspector", "reportPrivacy", "disconnectClearsInstances",
                      "selectedInstanceOwnerDetails", "rendererClassCopy", "highlightNativeButton",
                      "actualPrimaryOriginVerified", "runtimeLoaderVerified"):
            runtime[field] = True
        if change:
            change(runtime)
        if installed:
            relative = self.installed(target)
            child = self.write(str(Path(relative).parent / "smoke-result.json"), runtime)
            wrapper = json.loads((self.root / relative).read_text(encoding="utf-8"))
            wrapper["resultSha256"] = support.builder.digest(child)
            self.write(relative, wrapper)
        else:
            runtime.pop("loader")
            relative = target["runtimeEvidence"][1]
            child = self.write(relative, runtime)
        for name in runtime["screenshots"]:
            (child.parent / name).write_bytes(b"isolated offline test image")

    def test_all_eight_runner_outputs_are_first_in_shared_target_evidence(self):
        self.assertEqual(8, len(TARGETS))
        for target in TARGETS:
            with self.subTest(minecraft=target["minecraft"]):
                output = runner.installed_output(target)
                self.assertEqual(output / "installed-verification.json", self.root / target["runtimeEvidence"][0])
                self.assertEqual(2, len(target["runtimeEvidence"]))
                self.assertNotIn("/installed/", target["runtimeEvidence"][1])

    def test_profile_preparation_and_unfinished_world_never_count_as_passed(self):
        for target in TARGETS:
            with self.subTest(minecraft=target["minecraft"]):
                output = runner.installed_output(target)
                self.write(str(output.relative_to(self.root) / "preparation.json"), self.identity(target, "installed-production"))
                self.assertEqual([], self.both_readers(target))
                self.write(str(output.relative_to(self.root) / "smoke-result.json"), self.identity(target, "installed-production"))
                self.assertEqual([], self.both_readers(target), "A raw detail without launcher verification is not listed evidence")

    def test_installed_wrapper_and_linked_real_identity_are_accepted_for_all_eight(self):
        for target in TARGETS:
            with self.subTest(minecraft=target["minecraft"]):
                relative = self.installed(target)
                accepted = self.both_readers(target)
                self.assertEqual([relative], [proof["path"] for proof in accepted])
                self.assertEqual("installed-production", accepted[0]["environment"])

    def test_installed_is_preferred_without_relabeling_development_fallback(self):
        for target in TARGETS:
            with self.subTest(minecraft=target["minecraft"]):
                dev = self.identity(target, "fabric-development-client")
                dev.pop("loader")  # Established old development harness contract.
                self.write(target["runtimeEvidence"][1], dev)
                self.assertEqual("fabric-development-client", self.both_readers(target)[0]["environment"])
                relative = self.installed(target)
                accepted = self.both_readers(target)
                self.assertEqual([relative, target["runtimeEvidence"][1]], [proof["path"] for proof in accepted])
                self.assertEqual(["installed-production", "fabric-development-client"], [proof["environment"] for proof in accepted])

    def test_wrong_child_identity_scope_or_hash_never_promotes_installed_result(self):
        target = TARGETS[-1]
        mutations = [("success", False), ("minecraftVersion", "1.20.1"), ("loader", "NeoForge"),
                     ("loaderVersion", "0.0.0"), ("environment", "fabric-development-client"),
                     ("environment", "profile-preparation"), ("primaryJarSha256", "b" * 64),
                     ("artifactSha256", "b" * 64)]
        for key, value in mutations:
            with self.subTest(field=key, value=value):
                self.installed(target, lambda detail: detail.update({key: value}))
                self.assertEqual([], self.both_readers(target))
        self.installed(target, lambda detail: detail.pop("loader"))
        self.assertEqual([], self.both_readers(target), "Installed evidence must identify its actual loader explicitly")

    def test_missing_or_modified_child_and_failed_wrapper_are_rejected(self):
        target = TARGETS[0]
        relative = self.installed(target)
        child = (self.root / relative).parent / "smoke-result.json"
        child.write_bytes(child.read_bytes() + b" ")
        self.assertEqual([], self.both_readers(target))
        child.unlink()
        self.assertEqual([], self.both_readers(target))
        self.installed(target)
        wrapper = json.loads((self.root / relative).read_text(encoding="utf-8"))
        wrapper["success"] = False
        self.write(relative, wrapper)
        self.assertEqual([], self.both_readers(target))

    def test_runner_refuses_manifest_output_drift(self):
        target = copy.deepcopy(TARGETS[0])
        target["runtimeEvidence"] = target["runtimeEvidence"][1:]
        with self.assertRaises(RuntimeError):
            runner.installed_output(target)

    def test_build_verifier_accepts_installed_assertions_and_preserves_actual_scope(self):
        for target in (TARGETS[0], TARGETS[-1]):
            with self.subTest(minecraft=target["minecraft"]):
                self.complete_runtime(target)
                result = verifier.verify_runtime(target, {"sha256": self.sha, "bytes": 123}, support)
                self.assertTrue(result["runtimeVerified"])
                self.assertEqual("installed-production", result["runtimeScope"])
                self.assertEqual(target["runtimeEvidence"][0], result["runtimeEvidence"]["path"])

    def test_build_verifier_rejects_missing_installed_origin_ui_and_real_work_assertions(self):
        target = TARGETS[0]
        mutations = [("actualPrimaryOriginVerified", False), ("runtimeLoaderVerified", False),
                     ("rendererClassCopy", False), ("reportPrivacy", False), ("highlightNativeButton", False),
                     ("artifactBytes", 124), ("primaryJarBytes", 124), ("geometryAvailable", True),
                     ("gpuTimingAvailable", True), ("extractionScopes", 0), ("submissionScopes", 0)]
        for key, value in mutations:
            with self.subTest(field=key):
                self.complete_runtime(target, change=lambda runtime: runtime.update({key: value}))
                with self.assertRaises(SystemExit):
                    verifier.verify_runtime(target, {"sha256": self.sha, "bytes": 123}, support)

    def test_build_verifier_development_fallback_never_becomes_installed(self):
        target = TARGETS[0]
        self.complete_runtime(target, installed=False)
        result = verifier.verify_runtime(target, {"sha256": self.sha, "bytes": 123}, support)
        self.assertEqual("fabric-development-client", result["runtimeScope"])
        self.assertEqual(target["runtimeEvidence"][1], result["runtimeEvidence"]["path"])

    def test_build_verifier_cannot_pass_preparation_only(self):
        with self.assertRaises(SystemExit):
            verifier.verify_runtime(TARGETS[0], {"sha256": self.sha, "bytes": 123}, support)


if __name__ == "__main__":
    unittest.main()
