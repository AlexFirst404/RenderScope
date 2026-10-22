"""Release XML gate tests with isolated synthetic test-result files, not runtime data."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

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


if __name__ == "__main__":
    unittest.main()
