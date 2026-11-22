"""Regression tests for smart build selection; run with python3 -m unittest discover -s scripts."""
import contextlib
import io
import unittest
from unittest.mock import patch

import smart


class SmartTest(unittest.TestCase):
    def run_smart(self, changed, kind="test", extra=()):
        def git(*args):
            return "\n".join(changed) if args == ("diff", "--name-only", "base...HEAD") else ""

        with patch.object(smart, "git", side_effect=git), \
                patch.object(smart.sys, "argv", ["smart.py", kind, "--base", "base", *extra]), \
                patch.object(smart.subprocess, "call", return_value=0) as call, \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, smart.main())
        return call.call_args.args[0] if call.called else None

    def test_discovers_separate_optional_include(self):
        self.assertEqual("rtok-wasm", smart.modules().get(":rtok-wasm"))
        self.assertEqual("libs/http", smart.modules()[":http"])

    def test_wasm_edit_enables_and_tests_the_host(self):
        cmd = self.run_smart(["rtok-wasm/src/nativeMain/kotlin/rtok/plugins/wasm/WasmPlugin.kt"])
        self.assertIsNotNone(cmd)
        self.assertIn("-Prtok.wasm=true", cmd)
        self.assertIn(":rtok-wasm:macosArm64Test", cmd)
        self.assertIn(":rtok:cliTest", cmd)

    def test_full_run_does_not_drop_changed_wasm_module(self):
        cmd = self.run_smart(["settings.gradle.kts", "rtok-wasm/build.gradle.kts"])
        self.assertIn("-Prtok.wasm=true", cmd)
        self.assertIn("macosArm64Test", cmd)
        self.assertIn("e2eTest", cmd)

    def test_wasm_build_enables_the_host(self):
        cmd = self.run_smart(["rtok-wasm/build.gradle.kts"], "build")
        self.assertIsNotNone(cmd)
        self.assertIn("-Prtok.wasm=true", cmd)
        self.assertIn(":rtok-wasm:assemble", cmd)

    def test_default_full_run_keeps_wasm_opt_in(self):
        cmd = self.run_smart(["settings.gradle.kts"])
        self.assertNotIn("-Prtok.wasm=true", cmd)

    def test_shared_dependency_does_not_enable_unchanged_optional_module(self):
        cmd = self.run_smart(["rtok-sys/src/nativeMain/kotlin/rtok/sys/Proc.kt"])
        self.assertNotIn("-Prtok.wasm=true", cmd)
        self.assertNotIn(":rtok-wasm:macosArm64Test", cmd)

    def test_unchanged_tree_runs_nothing(self):
        self.assertIsNone(self.run_smart([]))


if __name__ == "__main__":
    unittest.main()
