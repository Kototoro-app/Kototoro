"""Regression checks for the pinned runtime archive reader (no network or JVM required)."""

import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile


spec = importlib.util.spec_from_file_location(
    "prepare_compatibility", Path(__file__).with_name("prepare-compatibility.py")
)
prepare_compatibility = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare_compatibility)


class PrepareCompatibilityTest(unittest.TestCase):
    def prepare_archive(self, entries, *, missing=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            pins = root / "mihon-desktop-compat/windows-runtime-pins.properties"
            pins.parent.mkdir()
            digest = hashlib.sha256(b"pinned runtime").hexdigest()
            pins.write_text(f"runtime-1.0.jar={digest}\n" + (f"missing.jar={digest}\n" if missing else ""))
            archive = root / "runtime.zip"
            with zipfile.ZipFile(archive, "w") as bundle:
                for name, data in entries:
                    bundle.writestr(name, data)
            destination = root / "output"
            with patch.object(prepare_compatibility, "__file__", str(root / "desktop-app/prepare-compatibility.py")):
                try:
                    prepare_compatibility.prepare(archive, destination)
                except ValueError:
                    self.assertFalse(destination.exists(), "Invalid archives must not write partial output")
                    raise
            self.assertEqual((destination / "runtime-1.0.jar").read_bytes(), b"pinned runtime")
            self.assertEqual(len(list(destination.iterdir())), 1)

    def test_identical_ordered_and_regular_copies_are_accepted(self):
        self.prepare_archive([
            ("App/app/001-runtime-1.0-0123456789abcdef.jar", b"pinned runtime"),
            ("App/app/runtime-1.0-0123456789abcdef.jar", b"pinned runtime"),
            ("App/native.dll", b"not extracted"),
        ])

    def test_single_copy_is_accepted(self):
        self.prepare_archive([("runtime-1.0.jar", b"pinned runtime")])

    def test_conflicting_duplicate_is_rejected_before_writing(self):
        with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
            self.prepare_archive([
                ("001-runtime-1.0.jar", b"pinned runtime"),
                ("runtime-1.0.jar", b"different runtime"),
            ])

    def test_missing_artifact_is_rejected_before_writing(self):
        with self.assertRaisesRegex(ValueError, "missing missing.jar"):
            self.prepare_archive([("runtime-1.0.jar", b"pinned runtime")], missing=True)


if __name__ == "__main__":
    unittest.main()
