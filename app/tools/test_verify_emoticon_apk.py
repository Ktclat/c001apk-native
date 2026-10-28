# SPDX-License-Identifier: AGPL-3.0-only
"""Offline negative checks using actual pinned artwork, never replacement images."""
from pathlib import Path
import shutil
import tempfile
import unittest
import warnings
import zipfile

import verify_emoticon_apk as verifier

ROOT = Path(__file__).absolute().parents[2]


class EmoticonInventoryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native-emoticon-inventory-")
        self.root = Path(self.temp.name)
        (self.root / verifier.MANIFEST).parent.mkdir(parents=True)
        shutil.copyfile(ROOT / verifier.MANIFEST, self.root / verifier.MANIFEST)
        shutil.copytree(ROOT / verifier.DIRECTORY, self.root / verifier.DIRECTORY)
        self.expected = verifier.read_manifest(self.root / verifier.MANIFEST)
        self.first = next(iter(self.expected))
        self.apk = self.root / "fixture.zip"

    def tearDown(self):
        self.temp.cleanup()

    def bundle(self, missing=False, extra=None, duplicate=False, corrupt=False, qualifier="drawable-nodpi-v4"):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.apk, "w") as archive:
                for name in self.expected:
                    if missing and name == self.first:
                        continue
                    data = (self.root / verifier.DIRECTORY / name).read_bytes()
                    if corrupt and name == self.first:
                        data += b"corrupt"
                    archive.writestr(f"res/{qualifier}/{name}", data)
                if duplicate:
                    archive.writestr(f"res/{qualifier}/{self.first}", (self.root / verifier.DIRECTORY / self.first).read_bytes())
                if extra:
                    archive.writestr(extra, (self.root / verifier.DIRECTORY / self.first).read_bytes())

    def test_actual_source_and_apk_inventory(self):
        self.bundle()
        verifier.verify(self.apk, self.root)

    def test_apk_missing_original(self):
        self.bundle(missing=True)
        with self.assertRaises(ValueError): verifier.verify_apk(self.apk, self.expected)

    def test_apk_duplicate_same_zip_entry(self):
        self.bundle(duplicate=True)
        with self.assertRaises(ValueError): verifier.verify_apk(self.apk, self.expected)

    def test_apk_duplicate_qualifier(self):
        self.bundle(extra=f"res/drawable-nodpi/{self.first}")
        with self.assertRaises(ValueError): verifier.verify_apk(self.apk, self.expected)

    def test_apk_extra_prefixed_art(self):
        self.bundle(extra="res/drawable-nodpi/c_unlisted.png")
        with self.assertRaises(ValueError): verifier.verify_apk(self.apk, self.expected)

    def test_apk_corrupt_original_bytes(self):
        self.bundle(corrupt=True)
        with self.assertRaises(ValueError): verifier.verify_apk(self.apk, self.expected)

    def test_apk_wrong_density(self):
        self.bundle(qualifier="drawable-hdpi")
        with self.assertRaises(ValueError): verifier.verify_apk(self.apk, self.expected)

    def test_source_missing_original(self):
        (self.root / verifier.DIRECTORY / self.first).unlink()
        with self.assertRaises(ValueError): verifier.verify_source(self.root, self.expected)

    def test_source_corrupt_bytes(self):
        with (self.root / verifier.DIRECTORY / self.first).open("ab") as stream: stream.write(b"corrupt")
        with self.assertRaises(ValueError): verifier.verify_source(self.root, self.expected)

    def test_source_unlisted_art(self):
        (self.root / verifier.DIRECTORY / "c_unlisted.png").write_bytes(b"unlisted")
        with self.assertRaises(ValueError): verifier.verify_source(self.root, self.expected)

    def test_source_qualified_alias(self):
        alias = self.root / "app/src/main/res/drawable-hdpi" / self.first
        alias.parent.mkdir()
        shutil.copyfile(self.root / verifier.DIRECTORY / self.first, alias)
        with self.assertRaises(ValueError): verifier.verify_source(self.root, self.expected)

    def test_source_symlink_original(self):
        original = self.root / verifier.DIRECTORY / self.first
        original.unlink()
        original.symlink_to(ROOT / verifier.DIRECTORY / self.first)
        with self.assertRaises(ValueError): verifier.verify_source(self.root, self.expected)

    def test_source_symlink_parent(self):
        alias = self.root / "alias"
        alias.symlink_to(self.root / "app", target_is_directory=True)
        with self.assertRaises(ValueError): verifier.read_manifest(alias / "tools/emoticons.sha256")

    def test_manifest_pin_duplicate_incomplete_and_path_rejected(self):
        original = (self.root / verifier.MANIFEST).read_bytes()
        for changed in (original + original.splitlines(keepends=True)[0],
                        b"\n".join(original.splitlines()[1:]), original.replace(b"  c_", b"  ../c_", 1)):
            (self.root / verifier.MANIFEST).write_bytes(changed)
            with self.assertRaises(ValueError): verifier.read_manifest(self.root / verifier.MANIFEST)

    def test_manifest_symlink_rejected(self):
        manifest = self.root / verifier.MANIFEST
        manifest.unlink()
        manifest.symlink_to(ROOT / verifier.MANIFEST)
        with self.assertRaises(ValueError): verifier.read_manifest(manifest)


if __name__ == "__main__":
    unittest.main()
