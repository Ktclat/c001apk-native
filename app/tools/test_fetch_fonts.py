"""Network-free tests; synthetic bytes only, never fetch the real fonts."""

import contextlib
import hashlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

import fetch_fonts as fonts


def fixture(payload=b"OTTO synthetic font fixture", name="fixture.otf"):
    blob = hashlib.sha1(f"blob {len(payload)}\0".encode() + payload, usedforsecurity=False).hexdigest()
    return fonts.Font(name, "Fixture.otf", len(payload), blob, hashlib.sha256(payload).hexdigest())


class PreparationTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.directory = Path(self.scratch.name) / "font"
        self.directory.mkdir()
        self.payload = b"OTTO synthetic font fixture"
        self.font = fixture(self.payload)
        self.target = self.directory / self.font.name
        self.output = io.StringIO()
        self.output_context = contextlib.redirect_stdout(self.output)
        self.output_context.__enter__()
        self.addCleanup(self.output_context.__exit__, None, None, None)

    def prepare(self, **kwargs):
        return fonts.prepare_fonts(self.directory, (self.font,), **kwargs)

    def write_download(self, font, output):
        self.assertEqual(font, self.font)
        self.assertFalse(self.target.exists())
        output.write(self.payload)

    def test_valid_existing_font_is_reused_online_without_download_or_write(self):
        self.target.write_bytes(self.payload)
        before = self.target.stat()
        download = mock.Mock(side_effect=AssertionError("Must not download"))
        self.prepare(download=download)
        download.assert_not_called()
        after = self.target.stat()
        self.assertEqual((before.st_ino, before.st_mtime_ns), (after.st_ino, after.st_mtime_ns))

    def test_valid_existing_font_is_reused_offline_without_download(self):
        self.target.write_bytes(self.payload)
        download = mock.Mock(side_effect=AssertionError("Must not download"))
        self.prepare(offline=True, download=download)
        download.assert_not_called()

    def test_missing_offline_font_does_not_create_a_directory_or_download(self):
        missing_dir = self.directory / "absent"
        download = mock.Mock(side_effect=AssertionError("Must not download"))
        with self.assertRaisesRegex(fonts.FontError, "Offline mode: missing"):
            fonts.prepare_fonts(missing_dir, (self.font,), offline=True, download=download)
        download.assert_not_called()
        self.assertFalse(missing_dir.exists())

    def test_same_size_corruption_fails_closed_even_online(self):
        corrupt = b"X" * self.font.size
        self.target.write_bytes(corrupt)
        download = mock.Mock(side_effect=AssertionError("Must not download"))
        with self.assertRaisesRegex(fonts.FontError, "checksum mismatch"):
            self.prepare(download=download)
        download.assert_not_called()
        self.assertEqual(self.target.read_bytes(), corrupt)
        self.assertEqual(list(self.directory.iterdir()), [self.target])

    def test_wrong_size_and_provenance_hash_fail(self):
        self.target.write_bytes(b"bad")
        with self.assertRaisesRegex(fonts.FontError, "wrong size"):
            fonts.verify_font(self.target, self.font)
        self.target.write_bytes(self.payload)
        altered = fonts.Font(self.font.name, self.font.upstream_name, self.font.size, "0" * 40, self.font.sha256)
        with self.assertRaisesRegex(fonts.FontError, "checksum mismatch"):
            fonts.verify_font(self.target, altered)

    def test_all_existing_fonts_are_checked_before_fetching_any_missing_font(self):
        missing = fixture(name="missing.otf")
        self.target.write_bytes(b"bad")
        download = mock.Mock(side_effect=AssertionError("Must not download"))
        with self.assertRaises(fonts.FontError):
            fonts.prepare_fonts(self.directory, (missing, self.font), download=download)
        download.assert_not_called()
        self.assertFalse((self.directory / missing.name).exists())

    def test_symlink_and_directory_targets_are_rejected_without_modification(self):
        destination = self.directory / "elsewhere"
        destination.write_bytes(self.payload)
        self.target.symlink_to(destination)
        with self.assertRaisesRegex(fonts.FontError, "non-symlink"):
            self.prepare(offline=True)
        self.assertEqual(destination.read_bytes(), self.payload)
        self.target.unlink()
        self.target.mkdir()
        with self.assertRaises(fonts.FontError):
            self.prepare(offline=True)
        self.assertTrue(self.target.is_dir())

    def test_complete_verified_bytes_are_published_atomically(self):
        real_link = fonts.os.link
        inode = []

        def checked_link(source, target, **kwargs):
            self.assertFalse(target.exists())
            fonts.verify_font(source, self.font)
            inode.append(source.stat().st_ino)
            real_link(source, target, **kwargs)
            self.assertEqual(target.read_bytes(), self.payload)

        with mock.patch.object(fonts.os, "link", side_effect=checked_link) as publish:
            self.prepare(download=self.write_download)
        publish.assert_called_once()
        self.assertEqual(self.target.stat().st_ino, inode[0])
        self.assertEqual(list(self.directory.iterdir()), [self.target])

    def test_invalid_download_is_never_published_and_temp_is_removed(self):
        def corrupt_download(font, output):
            output.write(b"X" * font.size)
        with mock.patch.object(fonts.os, "link") as publish:
            with self.assertRaisesRegex(fonts.FontError, "checksum mismatch"):
                self.prepare(download=corrupt_download)
        publish.assert_not_called()
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_interrupted_download_cleans_up_partial_temp(self):
        def interrupted(font, output):
            output.write(b"partial")
            raise OSError("interrupted fixture")
        with self.assertRaisesRegex(OSError, "interrupted fixture"):
            self.prepare(download=interrupted)
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_failed_publication_cleans_up_and_does_not_create_target(self):
        with mock.patch.object(fonts.os, "link", side_effect=OSError("fixture disk error")):
            with self.assertRaisesRegex(OSError, "fixture disk error"):
                self.prepare(download=self.write_download)
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_concurrent_valid_target_is_kept(self):
        inode = []
        def concurrent(font, output):
            output.write(self.payload)
            self.target.write_bytes(self.payload)
            inode.append(self.target.stat().st_ino)
        self.prepare(download=concurrent)
        self.assertEqual(self.target.stat().st_ino, inode[0])
        self.assertEqual(list(self.directory.iterdir()), [self.target])

    def test_concurrent_invalid_target_is_not_overwritten(self):
        corrupt = b"X" * self.font.size
        def concurrent(font, output):
            output.write(self.payload)
            self.target.write_bytes(corrupt)
        with self.assertRaisesRegex(fonts.FontError, "checksum mismatch"):
            self.prepare(download=concurrent)
        self.assertEqual(self.target.read_bytes(), corrupt)
        self.assertEqual(list(self.directory.iterdir()), [self.target])

    def test_main_offline_never_constructs_an_http_client_or_child(self):
        with mock.patch.object(fonts, "load_fonts", return_value=(self.font,)), \
             mock.patch.object(fonts.urllib.request, "build_opener") as opener, \
             mock.patch.object(fonts.subprocess, "run") as child, \
             contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(fonts.main(["--offline", "--font-dir", str(self.directory)]), 1)
            self.target.write_bytes(self.payload)
            self.assertEqual(fonts.main(["--offline", "--font-dir", str(self.directory)]), 0)
        opener.assert_not_called()
        child.assert_not_called()

    def test_font_directory_symlinks_are_rejected_online_and_offline(self):
        real = self.directory / "real"
        real.mkdir()
        (real / self.font.name).write_bytes(self.payload)
        alias = self.directory / "alias"
        alias.symlink_to(real, target_is_directory=True)
        download = mock.Mock(side_effect=AssertionError("Must not download"))
        for offline in (False, True):
            with self.subTest(offline=offline), self.assertRaisesRegex(fonts.FontError, "Unsafe font directory"):
                fonts.prepare_fonts(alias, (self.font,), offline=offline, download=download)
        download.assert_not_called()
        self.assertEqual((real / self.font.name).read_bytes(), self.payload)

    def test_symlinked_ancestor_is_rejected_before_mkdir_or_network(self):
        real = self.directory / "real"
        real.mkdir()
        alias = self.directory / "alias"
        alias.symlink_to(real, target_is_directory=True)
        download = mock.Mock(side_effect=AssertionError("Must not download"))
        with self.assertRaisesRegex(fonts.FontError, "Unsafe font directory"):
            fonts.prepare_fonts(alias / "missing" / "font", (self.font,), download=download)
        download.assert_not_called()
        self.assertEqual(list(real.iterdir()), [])

    def test_temp_symlink_replacement_cannot_redirect_real_child_stdout(self):
        victim = Path(self.scratch.name) / "must-not-change"
        original = b"unrelated file must never be truncated"
        victim.write_bytes(original)
        real_run = subprocess.run
        swapped = []

        def local_writer(command, **kwargs):
            return real_run([sys.executable, "-I", "-c",
                             "import sys; sys.stdout.buffer.write(" + repr(self.payload) + ")"], **kwargs)

        def replace_before_child(font, output):
            temp = next(self.directory.glob(".*.tmp"))
            temp.unlink()
            temp.symlink_to(victim)
            swapped.append(temp)
            with mock.patch.object(fonts.subprocess, "run", side_effect=local_writer):
                fonts.bounded_download(font, output)
            self.assertEqual(victim.read_bytes(), original)
            output.seek(0)
            self.assertEqual(output.read(), self.payload)

        with self.assertRaisesRegex(fonts.FontError, "Temporary font path was replaced"):
            self.prepare(download=replace_before_child)
        self.assertEqual(victim.read_bytes(), original)
        self.assertFalse(self.target.exists())
        # Cleanup deliberately leaves an entry we no longer own alone.
        self.assertTrue(swapped[0].is_symlink())

    def test_temp_regular_file_replacement_is_not_written_or_removed(self):
        replacement = b"replacement belongs to someone else"
        swapped = []
        def replace(font, output):
            temp = next(self.directory.glob(".*.tmp"))
            temp.unlink()
            temp.write_bytes(replacement)
            swapped.append(temp)
            output.write(self.payload)
        with self.assertRaisesRegex(fonts.FontError, "Temporary font path was replaced"):
            self.prepare(download=replace)
        self.assertEqual(swapped[0].read_bytes(), replacement)
        self.assertFalse(self.target.exists())

    def test_directory_replacement_does_not_redirect_output_or_cleanup(self):
        moved = self.directory.with_name("original-directory-moved")
        external = self.directory.with_name("external-directory")
        external.mkdir()
        victim = []
        def replace(font, output):
            temp = next(self.directory.glob(".*.tmp"))
            self.directory.rename(moved)
            self.directory.symlink_to(external, target_is_directory=True)
            unrelated = external / temp.name
            unrelated.write_bytes(b"do not unlink or truncate")
            victim.append(unrelated)
            output.write(self.payload)
        with self.assertRaisesRegex(fonts.FontError, "Unsafe font directory"):
            self.prepare(download=replace)
        self.assertEqual(victim[0].read_bytes(), b"do not unlink or truncate")
        self.assertFalse((external / self.font.name).exists())
        self.assertEqual(next(moved.glob(".*.tmp")).read_bytes(), self.payload)

    def test_destination_taking_hidden_cli_is_not_supported(self):
        with mock.patch.object(fonts.urllib.request, "build_opener") as opener, \
             mock.patch.object(fonts.subprocess, "run") as child, \
             contextlib.redirect_stderr(io.StringIO()):
            for flags in ([], ["--offline"]):
                with self.assertRaises(SystemExit) as stopped:
                    fonts.main(flags + ["--_download-font", self.font.name, str(self.target)])
                self.assertEqual(stopped.exception.code, 2)
        opener.assert_not_called()
        child.assert_not_called()
        self.assertFalse(self.target.exists())


class ManifestTests(unittest.TestCase):
    def test_checked_in_manifest_pins_exact_three_resource_names_and_commit(self):
        actual = fonts.load_fonts()
        self.assertEqual(len(actual), 3)
        self.assertEqual({font.name for font in actual}, set(fonts.SOURCES))
        for font in actual:
            self.assertTrue(font.url.startswith(
                "https://raw.githubusercontent.com/CodePlayer/webfont-noto/"
                "7af038d32866b387c64ef8e644abf5c303e48db6/"))
            self.assertRegex(font.sha256, r"^[0-9a-f]{64}$")
            self.assertRegex(font.git_blob, r"^[0-9a-f]{40}$")
            self.assertTrue(0 < font.size < 2_000_000)

    def test_missing_duplicate_unknown_and_malformed_manifest_entries_fail(self):
        valid = fonts.MANIFEST.read_text(encoding="utf-8")
        first = next(line for line in valid.splitlines() if not line.startswith("#"))
        variants = ["", valid + first + "\n", valid + "0" * 64 + "  unknown.otf\n",
                    valid.replace("  noto_sans_cjk_sc_regular.otf", "  ../regular.otf"),
                    valid.replace(first[:64], "g" * 64)]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fonts.sha256"
            for content in variants:
                with self.subTest(content=content):
                    path.write_text(content, encoding="utf-8")
                    with self.assertRaises(fonts.FontError):
                        fonts.load_fonts(path)


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        self.temp = Path(self.scratch.name) / "download.tmp"
        self.output = tempfile.TemporaryFile(mode="w+b")
        self.addCleanup(self.output.close)
        self.payload = b"OTTO synthetic font fixture"
        self.font = fixture(self.payload)

    def response(self, payload=None, **overrides):
        response = io.BytesIO(self.payload if payload is None else payload)
        response.status = overrides.get("status", 200)
        response.headers = overrides.get("headers", {})
        response.geturl = lambda: overrides.get("url", self.font.url)
        return response

    def fetch(self, response):
        opener = mock.Mock()
        opener.open.return_value = response
        with mock.patch.object(fonts.urllib.request, "build_opener", return_value=opener):
            fonts.download_to_stream(self.font, self.output)
        request = opener.open.call_args.args[0]
        self.assertEqual(request.full_url, self.font.url)
        self.assertEqual(opener.open.call_args.kwargs["timeout"], 20)

    def test_https_success_with_exact_length(self):
        self.fetch(self.response(headers={"Content-Length": str(self.font.size)}))
        fonts.verify_stream(self.output, self.font, "fixture")

    def test_missing_content_length_is_still_bounded_and_verified(self):
        self.fetch(self.response())
        fonts.verify_stream(self.output, self.font, "fixture")

    def test_truncation_and_oversize_fail(self):
        for payload, error in [(self.payload[:-1], "before the pinned"),
                               (self.payload + b"extra", "exceeds the pinned")]:
            self.output.seek(0)
            self.output.truncate(0)
            with self.subTest(error=error), self.assertRaisesRegex(fonts.FontError, error):
                self.fetch(self.response(payload))
        self.assertLessEqual(self.output.tell(), self.font.size)

    def test_bad_length_encoding_status_and_destination_fail_before_writing(self):
        variants = [dict(headers={"Content-Length": "9999999"}),
                    dict(headers={"Content-Encoding": "gzip"}), dict(status=206),
                    dict(url="http://example.invalid/not-https"),
                    dict(url="https://example.invalid/redirect")]
        for kwargs in variants:
            with self.subTest(kwargs=kwargs), self.assertRaises(fonts.FontError):
                self.fetch(self.response(**kwargs))
            self.assertEqual(self.output.tell(), 0)

    def test_redirects_are_rejected_even_to_another_https_url(self):
        for url in ("http://example.invalid/font", "https://example.invalid/font"):
            with self.assertRaisesRegex(fonts.FontError, "redirects"):
                fonts.NoRedirects().redirect_request(None, None, 302, "Found", {}, url)

    def test_child_invocation_has_hard_timeout_and_isolated_python(self):
        result = subprocess.CompletedProcess([], 0, "", "")
        with mock.patch.object(fonts.subprocess, "run", return_value=result) as run:
            fonts.bounded_download(self.font, self.output)
        self.assertEqual(run.call_args.kwargs["timeout"], 180)
        self.assertEqual(run.call_args.args[0][:2], [sys.executable, "-I"])
        self.assertEqual(run.call_args.args[0][2], "-c")
        self.assertIs(run.call_args.kwargs["stdout"], self.output)
        self.assertNotIn(str(self.temp), run.call_args.args[0])
        self.assertNotIn("--_download-font", run.call_args.args[0])

    def test_actual_local_child_timeout_is_reported_without_any_network(self):
        real_run = subprocess.run
        def local_sleep_only(command, **kwargs):
            # Replace the HTTP child with a local synthetic sleeper, but use the real timeout.
            return real_run([sys.executable, "-I", "-c", "import time; time.sleep(10)"], **kwargs)
        with mock.patch.object(fonts, "DOWNLOAD_TIMEOUT", 0.05), \
             mock.patch.object(fonts.subprocess, "run", side_effect=local_sleep_only), \
             self.assertRaisesRegex(fonts.FontError, "exceeded"):
            fonts.bounded_download(self.font, self.output)
        self.assertFalse(self.temp.exists())

    def test_isolated_child_loads_the_real_module_without_network(self):
        # Exercise the real runpy/module/manifest path; replace only the HTTP call.
        child = fonts.DOWNLOAD_CHILD.replace(
            "m['download_to_stream'](f, sys.stdout.buffer)",
            "sys.stdout.buffer.write(f.name.encode('ascii'))",
        )
        name = fonts.load_fonts()[0].name
        result = subprocess.run(
            [sys.executable, "-I", "-c", child, str(Path(fonts.__file__).absolute()), name],
            stdout=self.output, stderr=subprocess.PIPE, timeout=10, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.output.seek(0)
        self.assertEqual(self.output.read(), name.encode("ascii"))

    def test_nonzero_child_result_is_not_accepted(self):
        result = subprocess.CompletedProcess([], 1, "", "fixture failure")
        with mock.patch.object(fonts.subprocess, "run", return_value=result), \
             self.assertRaisesRegex(fonts.FontError, "fixture failure"):
            fonts.bounded_download(self.font, self.output)


if __name__ == "__main__":
    unittest.main()
