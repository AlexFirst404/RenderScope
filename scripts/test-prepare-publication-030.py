"""Offline release gate tests with isolated fixtures, never runtime evidence."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
import zipfile

spec = importlib.util.spec_from_file_location("preparer", Path(__file__).with_name("prepare-publication-030.py"))
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
OWNER = "dev.renderscope.core.RendererOwnershipTest"
REPLACEMENT = "dev.renderscope.core.BlockEntityReplacementTest"
OWNER_CASES = {"sharedImplementationKeepsIndependentOwnerRowsAndModDetailLookup",
               "rollingExpiryRemovesOnlyTheExpiredOwnersRendererAndIncludesZeroFrames",
               "capturePreservesBothOwnersAfterRollingExpiryAndIncludesEmptyFrames"}
REPLACEMENT_CASES = {"sampleContextQualifiesLegacyBlockKeysAndLiveLookupUsesTheSameIdentity",
                     "rollingInstancesAndDiagnosticDrilldownKeepEachActualOwnersCosts",
                     "capturePreservesReplacedHistoryWhileRollingExpiryAndZeroFramesRemainCorrect",
                     "differentTypesAtOnePositionInTheSameFrameKeepTheirObjectAndModGroups"}
EXPORTERS = {
    "root": ("dev.renderscope.client.report.ReportExporterTest",
             "sharedRendererExportPreservesSeparateOwnerRowsAndUnchangedClassIds",
             "replacedBlockTypesKeepSeparateCapturedInstancesWithoutExposingTheirKeys"),
    "modern": ("dev.renderscope.modern.ModernReportJsonTest",
               "sharedRendererExportPreservesBothRegistryOwnersWithoutChangingClassIds",
               "replacedBlockTypesKeepIndependentCaptureCostsWithoutRawInstanceKeys")}


class PublicationRegressionGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="renderscope-xml-gate-")
        self.previous_root = p.ROOT
        p.ROOT = Path(self.temporary.name)
        (p.ROOT / "tests").mkdir()

    def tearDown(self):
        p.ROOT = self.previous_root
        self.temporary.cleanup()

    def path(self, suite):
        return p.ROOT / "tests" / ("TEST-" + suite + ".xml")

    def write_suite(self, name, cases):
        suite = ET.Element("testsuite", name=name, tests=str(len(cases)), failures="0", errors="0", skipped="0")
        for case in sorted(cases):
            ET.SubElement(suite, "testcase", name=case + "()")
        ET.ElementTree(suite).write(self.path(name), encoding="utf-8")

    def complete(self, family):
        self.write_suite(OWNER, OWNER_CASES)
        self.write_suite(REPLACEMENT, REPLACEMENT_CASES)
        suite, owner, replacement = EXPORTERS[family]
        self.write_suite(suite, {owner, replacement})
        return {"project": "modern/mapped" if family == "modern" else ".", "testResults": "tests"}

    def test_both_families_require_four_replacement_cases_and_the_matching_export(self):
        for family in EXPORTERS:
            with self.subTest(family=family):
                result = p.ownership_regressions(self.complete(family))
                self.assertTrue(result["passed"])
                self.assertTrue(result["blockEntityReplacement"]["passed"])
                self.assertEqual(sorted(REPLACEMENT_CASES), result["blockEntityReplacement"]["requiredPassedCases"])
                self.assertEqual(EXPORTERS[family][2], result["blockEntityReplacement"]["requiredExporterCase"])
                self.assertEqual(3, len(result["suites"]))

    def test_old_outputs_without_the_replacement_suite_are_rejected(self):
        target = self.complete("root")
        self.path(REPLACEMENT).unlink()
        result = p.ownership_regressions(target)
        self.assertFalse(result["passed"])
        self.assertFalse(result["blockEntityReplacement"]["passed"])

    def test_one_missing_replacement_case_is_not_a_passing_regression_suite(self):
        target = self.complete("root")
        self.write_suite(REPLACEMENT, REPLACEMENT_CASES - {"differentTypesAtOnePositionInTheSameFrameKeepTheirObjectAndModGroups"})
        self.assertFalse(p.ownership_regressions(target)["passed"])

    def test_both_old_export_suites_without_replacement_export_are_rejected(self):
        for family, (suite, owner, _) in EXPORTERS.items():
            with self.subTest(family=family):
                target = self.complete(family)
                self.write_suite(suite, {owner})
                self.assertFalse(p.ownership_regressions(target)["passed"])

    def test_skipped_failed_or_errored_child_is_rejected_even_if_suite_counters_say_zero(self):
        for tag in ("skipped", "failure", "error"):
            with self.subTest(tag=tag):
                target = self.complete("modern")
                tree = ET.parse(self.path(REPLACEMENT))
                ET.SubElement(tree.getroot().find("testcase"), tag)
                tree.write(self.path(REPLACEMENT), encoding="utf-8")
                self.assertFalse(p.ownership_regressions(target)["passed"])

    def test_stale_inconsistent_test_count_is_rejected(self):
        target = self.complete("root")
        tree = ET.parse(self.path(REPLACEMENT))
        tree.getroot().set("tests", "3")
        tree.write(self.path(REPLACEMENT), encoding="utf-8")
        self.assertFalse(p.ownership_regressions(target)["passed"])


class PublicationManualVisualGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="renderscope-visual-gate-")
        self.previous_root = p.ROOT
        p.ROOT = Path(self.temporary.name).resolve()
        self.target = {"loader": "forge", "minecraft": "1.20.1", "loaderVersion": "47.4.10"}
        self.primary = "a" * 64
        self.result_path = "evidence/profile/smoke-result.json"
        self.result = {"success": True, "loader": "Forge", "minecraftVersion": "1.20.1", "loaderVersion": "47.4.10",
                       "environment": "installed-production", "primaryJarSha256": self.primary,
                       "screenshots": ["09-world-heatmap.png", "10-crosshair-inspector.png"]}
        images = []
        for name in self.result["screenshots"]:
            relative = "evidence/profile/gallery/" + name
            path = p.ROOT / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(("offline fixture: " + name).encode())
            images.append({"file": relative, "sha256": self.sha(relative)})
        self.write_json(self.result_path, self.result)
        self.row = {**self.target, "primaryJarSha256": self.primary, "smokeResultSha256": self.sha(self.result_path),
                    "reviewedAt": "2026-10-22T20:00:00+00:00", "worldBoxesAligned": True,
                    "labelsReadable": True, "inspectorVisible": True, "screenshots": images}
        self.manifest_path = p.VISUAL_MANIFESTS[0]
        self.manifest = {"schemaVersion": 1, "profiles": [self.row]}

    def tearDown(self):
        p.ROOT = self.previous_root
        self.temporary.cleanup()

    def sha(self, relative):
        return hashlib.sha256((p.ROOT / relative).read_bytes()).hexdigest()

    def write_json(self, relative, document):
        path = p.ROOT / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document), encoding="utf-8")

    def evidence(self):
        return [{"path": self.result_path, "sha256": self.sha(self.result_path), "environment": "installed-production"}]

    def verify(self, evidence=None):
        self.write_json(self.manifest_path, self.manifest)
        return p.manual_visual_review(self.target, self.primary, self.evidence() if evidence is None else evidence,
                                      p.load_visual_reviews())

    def test_complete_current_direct_result_and_image_review_is_accepted(self):
        result = self.verify()
        self.assertTrue(result["passed"])
        self.assertEqual(self.sha(self.result_path), result["resultSha256"])
        self.assertEqual(self.sha(self.manifest_path), result["manifestSha256"])
        self.assertEqual(self.row["screenshots"], result["screenshots"])

    def test_current_verifier_must_link_exact_result_hash(self):
        verifier = {**self.target, "loader": "Forge", "minecraftVersion": "1.20.1", "success": True,
                    "primaryJarSha256": self.primary, "resultFile": "smoke-result.json",
                    "resultSha256": self.sha(self.result_path)}
        path = "evidence/profile/installed-verification.json"
        self.write_json(path, verifier)
        evidence = [{"path": path, "sha256": self.sha(path), "environment": "installed-production"}]
        self.assertTrue(self.verify(evidence)["passed"])
        verifier["resultSha256"] = "b" * 64
        self.write_json(path, verifier)
        evidence[0]["sha256"] = self.sha(path)
        self.assertFalse(self.verify(evidence)["passed"])

    def test_false_or_missing_visual_approval_is_rejected(self):
        for field in ("worldBoxesAligned", "labelsReadable", "inspectorVisible"):
            for value in (False, None, "true", 1):
                with self.subTest(field=field, value=value):
                    self.row[field] = value
                    self.assertFalse(self.verify()["passed"])
            self.row[field] = True

    def test_wrong_primary_image_or_result_hash_is_rejected(self):
        for field in ("primaryJarSha256", "smokeResultSha256"):
            with self.subTest(field=field):
                saved = self.row[field]
                self.row[field] = "b" * 64
                self.assertFalse(self.verify()["passed"])
                self.row[field] = saved
        self.row["screenshots"][0]["sha256"] = "b" * 64
        self.assertFalse(self.verify()["passed"])

    def test_changed_image_bytes_are_rejected_without_trusting_filename(self):
        (p.ROOT / self.row["screenshots"][0]["file"]).write_bytes(b"different framebuffer bytes")
        self.assertFalse(self.verify()["passed"])

    def test_different_loader_minecraft_or_loader_version_is_rejected(self):
        for field, wrong in (("loader", "NeoForge"), ("minecraftVersion", "1.20.2"), ("loaderVersion", "47.1.106")):
            with self.subTest(field=field):
                wrong_result = {**self.result, field: wrong}
                self.write_json(self.result_path, wrong_result)
                self.row["smokeResultSha256"] = self.sha(self.result_path)
                self.assertFalse(self.verify()["passed"])

    def test_review_from_another_optional_profile_cannot_approve_same_primary(self):
        other = "evidence/other-profile/smoke-result.json"
        self.write_json(other, {**self.result, "profile": "another actual run"})
        self.row["smokeResultSha256"] = self.sha(other)
        self.row["resultFile"] = other
        self.assertFalse(self.verify()["passed"])

    def test_missing_complete_review_is_rejected_but_narrow_optional_does_not_block_full_review(self):
        narrow = copy.deepcopy(self.row)
        narrow["inspectorVisible"] = None
        self.manifest["profiles"] = [narrow]
        self.assertFalse(self.verify()["passed"])
        self.manifest["profiles"].append(self.row)
        self.assertTrue(self.verify()["passed"])

    def test_world_only_or_unlisted_image_cannot_substitute_for_inspector(self):
        inspector = self.row["screenshots"].pop()
        self.assertFalse(self.verify()["passed"])
        self.row["screenshots"].append(inspector)
        self.result["screenshots"].remove("10-crosshair-inspector.png")
        self.write_json(self.result_path, self.result)
        self.row["smokeResultSha256"] = self.sha(self.result_path)
        self.assertFalse(self.verify()["passed"])

    def test_escaping_image_path_or_changed_manifest_is_rejected(self):
        self.row["screenshots"][0]["file"] = "../outside.png"
        self.assertFalse(self.verify()["passed"])
        self.row["screenshots"][0]["file"] = "evidence/profile/gallery/09-world-heatmap.png"
        self.assertTrue(self.verify()["passed"])
        reviews = p.load_visual_reviews()
        (p.ROOT / self.manifest_path).write_text("{}")
        self.assertFalse(p.manual_visual_review(self.target, self.primary, self.evidence(), reviews)["passed"])

    def test_mc21_existing_explicit_aliases_and_image_hash_map_are_supported(self):
        row = copy.deepcopy(self.row)
        row["boundingBoxesVisibleAndAligned"] = row.pop("worldBoxesAligned")
        row["inspectorAndTrackingVisible"] = row.pop("inspectorVisible")
        row["screenshotsSha256"] = {Path(i["file"]).name: i["sha256"] for i in row.pop("screenshots")}
        row.pop("loader")
        row.pop("minecraft")
        self.target["loader"] = "fabric"
        self.result["loader"] = "Fabric"
        self.write_json(self.result_path, self.result)
        row["smokeResultSha256"] = self.sha(self.result_path)
        self.write_json(p.VISUAL_MANIFESTS[1], {"schemaVersion": 1, "targets": {"1.20.1": row}})
        self.assertTrue(p.manual_visual_review(self.target, self.primary, self.evidence(), p.load_visual_reviews())["passed"])

    def test_git_byte_gate_includes_manual_manifest_and_reviewed_pngs(self):
        visual = self.verify()
        files = p.git_evidence_files([{"runtimeEvidence": self.evidence(), "manualVisualReview": visual}], [])
        paths = {f["path"] for f in files}
        self.assertTrue({self.manifest_path, self.result_path, *(i["file"] for i in self.row["screenshots"])}.issubset(paths))

    def test_all_numerical_worlds_cannot_publish_with_one_failed_or_stale_manual_review(self):
        rows = [{"loader": "forge", "minecraft": f"fixture-{i}", "sha256": self.primary,
                 "worldVerified": True, "packageIndexMatches": True, "rendererOwnerIsolationPresent": True,
                 "rendererOwnerRegressions": {"passed": True, "blockEntityReplacement": {"passed": True}},
                 "visuallyVerified": True, "manualVisualReview": {"passed": True, "primaryJarSha256": self.primary}}
                for i in range(67)]
        self.assertTrue(p.publication_ready(rows, {"passed": True}))
        rows[-1]["manualVisualReview"]["passed"] = False
        self.assertFalse(p.publication_ready(rows, {"passed": True}))
        rows[-1]["manualVisualReview"]["passed"] = True
        rows[-1]["manualVisualReview"]["primaryJarSha256"] = "b" * 64
        self.assertFalse(p.publication_ready(rows, {"passed": True}))
        rows[-1]["manualVisualReview"]["primaryJarSha256"] = self.primary
        self.assertFalse(p.publication_ready(rows, {"passed": False}))
        rows[-1]["minecraft"] = rows[0]["minecraft"]
        self.assertFalse(p.publication_ready(rows, {"passed": True}))

    def test_modern_publication_row_requires_real_hud_validation_even_with_manual_boolean(self):
        rows = [{"loader": "forge", "minecraft": f"fixture-{i}", "sha256": self.primary,
                 "worldVerified": True, "packageIndexMatches": True, "rendererOwnerIsolationPresent": True,
                 "rendererOwnerRegressions": {"passed": True, "blockEntityReplacement": {"passed": True}},
                 "visuallyVerified": True, "manualVisualReview": {"passed": True, "primaryJarSha256": self.primary}}
                for i in range(67)]
        rows[-1]["minecraft"] = "1.21.9"
        self.assertFalse(p.publication_ready(rows, {"passed": True}))
        rows[-1]["modernInspectorHudFrameValidationVerified"] = True
        self.assertTrue(p.publication_ready(rows, {"passed": True}))

    def test_missing_or_malformed_manual_manifest_does_not_approve_runtime(self):
        for raw in (None, "not JSON", "[]", "{}"):
            with self.subTest(raw=raw):
                path = p.ROOT / self.manifest_path
                if raw is None:
                    path.unlink(missing_ok=True)
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(raw)
                self.assertFalse(p.manual_visual_review(self.target, self.primary, self.evidence(), p.load_visual_reviews())["passed"])

    def test_font_fix_note_requires_exact_target_implementation_and_current_visual_pass(self):
        artifact = p.ROOT / "fixture.jar"
        with zipfile.ZipFile(artifact, "w") as jar:
            jar.writestr("dev/renderscope/client/world/WorldOverlay.class", b"offline descriptor fixture: WorldOverlay$LabelPlacement;Z)V")
        target = {**self.target, "minecraft": "1.21.6"}
        self.assertEqual(([], []), p.target_release_fixes(target, artifact, {"passed": False}))
        en, ru = p.target_release_fixes(target, artifact, {"passed": True})
        self.assertIn("1.21.6", en[0])
        self.assertIn("1.21.6", ru[0])
        self.assertEqual(([], []), p.target_release_fixes({**target, "minecraft": "1.21.7"}, artifact, {"passed": True}))
        with zipfile.ZipFile(artifact, "w") as jar:
            jar.writestr("dev/renderscope/client/world/WorldOverlay.class", b"old single-pass descriptor fixture")
        self.assertEqual(([], []), p.target_release_fixes(target, artifact, {"passed": True}))

    def modern_result(self):
        self.target["minecraft"] = "1.21.9"
        self.row["minecraft"] = "1.21.9"
        self.result["minecraftVersion"] = "1.21.9"

    def refresh_result(self):
        self.write_json(self.result_path, self.result)
        self.row["smokeResultSha256"] = self.sha(self.result_path)

    def test_modern_manual_review_requires_real_draw_f1_hide_restore_and_positive_counter(self):
        self.modern_result()
        self.refresh_result()
        self.assertFalse(self.verify()["passed"])
        flags = {"actualInspectorHudDrawn": True, "f1HidesInspectorHud": True,
                 "inspectorResumesAfterF1": True, "inspectorHudDrawPasses": 3}
        self.result.update(flags)
        self.refresh_result()
        self.assertTrue(self.verify()["passed"])
        for flag in flags:
            with self.subTest(flag=flag):
                self.result[flag] = 0 if flag == "inspectorHudDrawPasses" else False
                self.refresh_result()
                self.assertFalse(self.verify()["passed"])
                self.result[flag] = flags[flag]

    def test_modern_hud_note_is_bound_to_actual_current_jar_and_same_verified_result(self):
        self.modern_result()
        artifact = p.ROOT / "hud-fixture.jar"
        artifact.write_bytes(b"offline current JAR fingerprint fixture")
        self.primary = self.sha("hud-fixture.jar")
        self.row["primaryJarSha256"] = self.primary
        self.result.update({"primaryJarSha256": self.primary, "actualInspectorHudDrawn": True,
                            "f1HidesInspectorHud": True, "inspectorResumesAfterF1": True, "inspectorHudDrawPasses": 7})
        self.refresh_result()
        visual = self.verify()
        self.assertTrue(visual["passed"])
        self.assertTrue(p.modern_inspector_fix_verified(self.target, artifact, visual))
        en, ru = p.target_release_fixes(self.target, artifact, visual)
        self.assertTrue(any("early-return" in note and "F1" in note for note in en))
        self.assertTrue(any("F1" in note for note in ru))
        self.result["inspectorHudDrawPasses"] = 0
        self.write_json(self.result_path, self.result)
        self.assertFalse(p.modern_inspector_fix_verified(self.target, artifact, visual))
        self.result["inspectorHudDrawPasses"] = 7
        self.write_json(self.result_path, self.result)
        artifact.write_bytes(b"a different rebuilt JAR")
        self.assertFalse(p.modern_inspector_fix_verified(self.target, artifact, visual))

    def test_earlier_manual_review_contract_stays_valid_without_modern_fields(self):
        self.assertTrue(self.verify()["passed"])
        for minecraft in ("1.20.1", "1.20.6", "1.21", "1.21.8"):
            self.assertFalse(p.modern_inspector_result_verified(minecraft, {
                "actualInspectorHudDrawn": True, "f1HidesInspectorHud": True,
                "inspectorResumesAfterF1": True, "inspectorHudDrawPasses": 1}))

    def neo_selector_fixture(self, minecraft, boolean=False, short_selector=False):
        self.target.update({"minecraft": minecraft, "loader": "neoforge",
                            "loaderVersion": p.NEOFORGE_BLOCK_ENTITY_SELECTOR_TARGETS[minecraft]})
        self.row.update({"minecraft": minecraft, "loader": "neoforge"})
        artifact = p.ROOT / "neo-selector-fixture.jar"
        descriptor = ("tryExtractRenderState(Lnet/minecraft/world/level/block/entity/BlockEntity;F"
                      "Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;"
                      + ("Z" if boolean else "") + "Lnet/minecraft/client/renderer/culling/Frustum;)"
                      "Lnet/minecraft/client/renderer/blockentity/state/BlockEntityRenderState;")
        with zipfile.ZipFile(artifact, "w") as jar:
            jar.writestr("dev/renderscope/modern/mixin/BlockEntityDispatcherMixin.class",
                         b"tryExtractRenderState" if short_selector else descriptor.encode("ascii"))
        self.primary = self.sha("neo-selector-fixture.jar")
        self.row["primaryJarSha256"] = self.primary
        self.result.update({"minecraftVersion": minecraft, "loader": "NeoForge",
            "loaderVersion": self.target["loaderVersion"], "primaryJarSha256": self.primary,
            "actualInspectorHudDrawn": True, "f1HidesInspectorHud": True,
            "inspectorResumesAfterF1": True, "inspectorHudDrawPasses": 5,
            "entityExtractionCalls": 7, "entitySubmissionCalls": 7,
            "blockEntityExtractionCalls": 11, "blockEntitySubmissionCalls": 11})
        self.refresh_result()
        return artifact, self.verify()

    def test_neo_note_requires_exact_pinned_frumstum_overload_and_four_positive_runtime_sources(self):
        for minecraft in p.NEOFORGE_BLOCK_ENTITY_SELECTOR_TARGETS:
            with self.subTest(minecraft=minecraft):
                artifact, visual = self.neo_selector_fixture(minecraft, boolean=minecraft in ("26.2", "26.3"))
                self.assertTrue(visual["passed"])
                self.assertTrue(p.neoforge_block_entity_selector_verified(self.target, artifact, visual))
                en, ru = p.target_release_fixes(self.target, artifact, visual)
                self.assertTrue(any("Frustum" in note for note in en))
                self.assertTrue(any("Frustum" in note for note in ru))
        artifact, _ = self.neo_selector_fixture("1.21.9")
        for field in ("entityExtractionCalls", "entitySubmissionCalls", "blockEntityExtractionCalls", "blockEntitySubmissionCalls"):
            with self.subTest(field=field):
                good = self.result[field]
                self.result[field] = 0
                self.refresh_result()
                self.assertFalse(p.neoforge_block_entity_selector_verified(self.target, artifact, self.verify()))
                self.result[field] = good

    def test_neo_delegating_selector_wrong_boolean_or_uninspected_loader_cannot_claim_fix(self):
        artifact, visual = self.neo_selector_fixture("1.21.9", short_selector=True)
        self.assertFalse(p.neoforge_block_entity_selector_verified(self.target, artifact, visual))
        artifact, visual = self.neo_selector_fixture("26.3", boolean=False)
        self.assertFalse(p.neoforge_block_entity_selector_verified(self.target, artifact, visual))
        artifact, visual = self.neo_selector_fixture("26.3", boolean=True)
        self.assertFalse(p.neoforge_block_entity_selector_verified({**self.target, "loaderVersion": "unknown"}, artifact, visual))
        self.assertFalse(p.neoforge_block_entity_selector_verified({**self.target, "loader": "forge"}, artifact, visual))


if __name__ == "__main__":
    unittest.main()
