"""Archive integrity checks for the macOS playback bundler; no network or native tools."""
import hashlib
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("prepare_macos_mpv", Path(__file__).with_name("prepare-macos-mpv.py"))
bundler = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bundler)


class PrepareMacosMpvTest(unittest.TestCase):
    def read_archive(self, entries):
        buffer = io.BytesIO()
        with tarfile.open(fileobj=buffer, mode="w") as archive:
            for name, payload, target in entries:
                item = tarfile.TarInfo(name)
                if target is not None:
                    item.type = tarfile.SYMTYPE
                    item.linkname = target
                else:
                    item.size = len(payload)
                archive.addfile(item, io.BytesIO(payload) if target is None else None)
        buffer.seek(0)
        with tarfile.open(fileobj=buffer) as archive:
            return bundler.archive_files(archive)

    def test_local_library_alias_is_materialized(self):
        files = self.read_archive([
            ("release/lib/libmpv.2.dylib", b"library", None),
            ("release/lib/libmpv.dylib", b"", "libmpv.2.dylib"),
        ])
        self.assertEqual(files["lib/libmpv.dylib"], b"library")

    def test_traversal_and_link_escape_are_rejected(self):
        for entry in [("release/lib/../../outside", b"bad", None),
                      ("release/lib/alias", b"", "../../../outside")]:
            with self.subTest(entry=entry), self.assertRaises(ValueError):
                self.read_archive([entry])

    def test_link_cycle_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "cycle"):
            self.read_archive([("release/lib/a", b"", "b"), ("release/lib/b", b"", "a")])

    def test_supplied_archive_hash_must_match_before_use(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "runtime.tar.gz"
            archive.write_bytes(b"unexpected archive")
            pin = {"name": archive.name, "sha256": hashlib.sha256(b"expected archive").hexdigest()}
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                bundler.fetch(pin, {}, root / "cache", archive)
            self.assertEqual(archive.read_bytes(), b"unexpected archive")


if __name__ == "__main__":
    unittest.main()
