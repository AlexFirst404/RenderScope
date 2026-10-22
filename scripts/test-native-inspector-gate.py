"""Offline modern native HUD-result gate regression tests; no client launch."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("native_launcher", Path(__file__).with_name("test-native-client.py"))
launcher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(launcher)


class NativeInspectorGateTest(unittest.TestCase):
    def test_modern_boundaries_require_real_draw_f1_hide_and_resume(self):
        good = {"actualInspectorHudDrawn": True, "f1HidesInspectorHud": True,
                "inspectorResumesAfterF1": True, "inspectorHudDrawPasses": 3}
        for minecraft in ("1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"):
            with self.subTest(minecraft=minecraft):
                launcher.verify_modern_inspector(minecraft, good)
                for flag in ("actualInspectorHudDrawn", "f1HidesInspectorHud", "inspectorResumesAfterF1"):
                    with self.assertRaises(RuntimeError):
                        launcher.verify_modern_inspector(minecraft, {**good, flag: False})
                for passes in (None, 0, -1, True, "3"):
                    with self.assertRaises(RuntimeError):
                        launcher.verify_modern_inspector(minecraft, {**good, "inspectorHudDrawPasses": passes})

    def test_older_contracts_do_not_acquire_modern_only_fields(self):
        for minecraft in ("1.20.1", "1.20.2", "1.20.6", "1.21", "1.21.1", "1.21.5", "1.21.8"):
            launcher.verify_modern_inspector(minecraft, {})


if __name__ == "__main__":
    unittest.main()
