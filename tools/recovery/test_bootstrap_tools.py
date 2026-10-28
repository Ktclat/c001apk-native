"""Network-free tests of the new setup boundaries; no Gradle or Android process."""
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SOURCE = Path(__file__).resolve().parent


class SetupBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.modules = {}
        with patch.dict(os.environ, {'NATIVE_TOOLCHAIN_ROOT': str(self.root)}):
            for name in ('bootstrap_downloads', 'install_archives'):
                spec = importlib.util.spec_from_file_location(name, SOURCE / (name + '.py'))
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                self.modules[name] = module
        self.downloader = self.modules['bootstrap_downloads']
        self.installer = self.modules['install_archives']

    def archive(self, name='top/file.txt', mode=0o100644):
        output = io.BytesIO()
        with zipfile.ZipFile(output, 'w') as z:
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = mode << 16
            z.writestr(info, b'fixture')
        return output.getvalue()

    def item(self, data):
        return {'filename': 'fixture.zip', 'url': 'https://example.invalid/never-requested',
                'bytes': len(data), 'algorithm': 'sha256',
                'digest': hashlib.sha256(data).hexdigest(), 'sha256': hashlib.sha256(data).hexdigest()}

    def fake_curl(self, data):
        def run(command, **kwargs):
            self.assertEqual(command[0], 'curl')
            Path(command[-1]).write_bytes(data)
        return run

    def test_existing_exact_archive_reused_without_network(self):
        data = self.archive(); (self.downloader.D / 'fixture.zip').write_bytes(data)
        with patch.object(self.downloader.subprocess, 'run', side_effect=AssertionError('No network')):
            receipt = self.downloader.fetch(self.item(data))
        self.assertEqual(receipt['actualBytes'], len(data))

    def test_existing_mismatch_is_not_overwritten(self):
        target = self.downloader.D / 'fixture.zip'; target.write_bytes(b'wrong')
        with patch.object(self.downloader.subprocess, 'run', side_effect=AssertionError('No network')):
            with self.assertRaises(RuntimeError): self.downloader.fetch(self.item(self.archive()))
        self.assertEqual(target.read_bytes(), b'wrong')

    def test_download_published_only_after_verification(self):
        data = self.archive()
        with patch.object(self.downloader.subprocess, 'run', side_effect=self.fake_curl(data)):
            self.downloader.fetch(self.item(data))
        self.assertEqual((self.downloader.D / 'fixture.zip').read_bytes(), data)
        self.assertEqual(list(self.downloader.D.glob('*.partial')), [])

    def test_truncated_download_not_published(self):
        data = self.archive()
        with patch.object(self.downloader.subprocess, 'run', side_effect=self.fake_curl(data[:-1])):
            with self.assertRaises(RuntimeError): self.downloader.fetch(self.item(data))
        self.assertFalse((self.downloader.D / 'fixture.zip').exists())
        self.assertEqual(len(list(self.downloader.D.glob('*.partial'))), 1)

    def test_invalid_zip_with_consistent_hash_not_published(self):
        data = b'not a ZIP'
        with patch.object(self.downloader.subprocess, 'run', side_effect=self.fake_curl(data)):
            with self.assertRaises(zipfile.BadZipFile): self.downloader.fetch(self.item(data))
        self.assertFalse((self.downloader.D / 'fixture.zip').exists())

    def install(self, data):
        (self.installer.D / 'fixture.zip').write_bytes(data)
        self.installer.install_zip('fixture.zip', self.root / 'installed')

    def test_extraction_retains_executable_permission(self):
        self.install(self.archive(mode=0o100755))
        self.assertEqual((self.root / 'installed/file.txt').stat().st_mode & 0o777, 0o755)

    def test_traversal_rejected(self):
        with self.assertRaises(RuntimeError): self.install(self.archive('../escape.txt'))
        self.assertFalse((self.root / 'escape.txt').exists())
        self.assertFalse((self.root / 'installed').exists())

    def test_absolute_path_rejected(self):
        with self.assertRaises(RuntimeError): self.install(self.archive('/absolute.txt'))
        self.assertFalse((self.root / 'installed').exists())

    def test_zip_symlink_rejected(self):
        with self.assertRaises(RuntimeError): self.install(self.archive(mode=stat.S_IFLNK | 0o777))
        self.assertFalse((self.root / 'installed').exists())

    def test_existing_installation_is_not_overwritten(self):
        target = self.root / 'installed'; target.mkdir(); (target / 'keep').write_bytes(b'old')
        with self.assertRaises(RuntimeError): self.install(self.archive())
        self.assertEqual((target / 'keep').read_bytes(), b'old')


if __name__ == '__main__':
    unittest.main()
