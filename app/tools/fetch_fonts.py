#!/usr/bin/env python3
"""Prepare the three pinned Noto fonts. Python 3 standard library only.

Existing files are verified, never silently replaced. --offline never downloads.
The Android build independently checks fonts.sha256 and never invokes this script.
"""

import argparse
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import urllib.request

COMMIT = "7af038d32866b387c64ef8e644abf5c303e48db6"
BASE_URL = (
    f"https://raw.githubusercontent.com/CodePlayer/webfont-noto/{COMMIT}/"
    "dist/NotoSans/NotoSansCJKsc-hinted/subset/NotoSansCJKsc-hinted-standard"
)
TOOLS_DIR = Path(__file__).absolute().parent
FONT_DIR = TOOLS_DIR.parent / "src/main/res/font"
MANIFEST = TOOLS_DIR / "fonts.sha256"
DOWNLOAD_TIMEOUT = 180  # Hard per-font wall-clock bound, including DNS and headers.
SOCKET_TIMEOUT = 20
CHUNK_SIZE = 64 * 1024
# Android resource name -> original filename, exact bytes, provenance Git-blob SHA-1.
SOURCES = {
    "noto_sans_cjk_sc_regular.otf": (
        "NotoSansCJKsc-Regular.otf", 1885732, "db41e65196f7ac227ace4a88b290d925587ed9be"
    ),
    "noto_sans_cjk_sc_medium.otf": (
        "NotoSansCJKsc-Medium.otf", 1883708, "df0844dd98e040db25dfdb62a61975745b183844"
    ),
    "noto_sans_cjk_sc_bold.otf": (
        "NotoSansCJKsc-Bold.otf", 1937204, "2c02c2a70768918337ba8125070abf60020b8360"
    ),
}


class FontError(Exception):
    """A missing, invalid, or unobtainable pinned build resource."""


@dataclass(frozen=True)
class Font:
    name: str
    upstream_name: str
    size: int
    git_blob: str
    sha256: str

    @property
    def url(self):
        return f"{BASE_URL}/{self.upstream_name}"


def load_fonts(manifest=MANIFEST):
    hashes = {}
    for line in manifest.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-z0-9_]+\.otf)", line)
        if not match or match[2] not in SOURCES or match[2] in hashes:
            raise FontError(f"Invalid or duplicate entry in {manifest}")
        hashes[match[2]] = match[1]
    if set(hashes) != set(SOURCES):
        raise FontError(f"{manifest} must pin exactly Regular, Medium, and Bold")
    return tuple(Font(name, *source, hashes[name]) for name, source in SOURCES.items())


def directory_identity(path, allow_missing=False):
    """Reject every symlink component without resolving it away first."""
    path = path.absolute()
    identity = []
    for component in reversed((path, *path.parents)):
        try:
            info = component.lstat()
        except FileNotFoundError:
            if allow_missing:
                continue
            raise FontError(f"Missing font directory component: {component}")
        if not stat.S_ISDIR(info.st_mode):
            raise FontError(f"Unsafe font directory component (symlink or non-directory): {component}")
        identity.append((component, info.st_dev, info.st_ino))
    return tuple(identity)


def check_owned_temp(path, stream):
    expected = os.fstat(stream.fileno())
    actual = path.lstat()
    if not stat.S_ISREG(actual.st_mode) or (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino):
        raise FontError(f"Temporary font path was replaced: {path}")


def verify_stream(stream, font, label):
    if os.fstat(stream.fileno()).st_size != font.size:
        raise FontError(f"{label}: wrong size (expected {font.size} bytes)")
    sha256 = hashlib.sha256()
    # SHA-256 is the integrity requirement; SHA-1 additionally matches upstream provenance.
    blob = hashlib.sha1(f"blob {font.size}\0".encode("ascii"), usedforsecurity=False)
    stream.seek(0)
    for chunk in iter(lambda: stream.read(CHUNK_SIZE), b""):
        sha256.update(chunk)
        blob.update(chunk)
    if sha256.hexdigest() != font.sha256 or blob.hexdigest() != font.git_blob:
        raise FontError(f"{label}: pinned font checksum mismatch; leaving it unchanged")


def verify_font(path, font):
    directory_identity(path.parent)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode):
        raise FontError(f"{path}: expected a regular, non-symlink font file")
    # Opening read-only can never truncate a swapped path; reject a changed inode as well.
    fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    with os.fdopen(fd, "rb") as stream:
        check_owned_temp(path, stream)
        verify_stream(stream, font, path)


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise FontError("Font download redirects are not permitted")


def download_to_stream(font, output):
    """Child-process operation: bounded bytes, HTTPS certificate checks, no redirects."""
    opener = urllib.request.build_opener(NoRedirects())
    request = urllib.request.Request(font.url, headers={
        "User-Agent": "c001apk-native-font-preparer/1",
        "Accept-Encoding": "identity",
    })
    with opener.open(request, timeout=SOCKET_TIMEOUT) as response:
        if response.status != 200 or response.geturl() != font.url:
            raise FontError("Unexpected font download response or destination")
        if response.headers.get("Content-Encoding", "identity") != "identity":
            raise FontError("Encoded font download rejected")
        length = response.headers.get("Content-Length")
        if length is not None and length != str(font.size):
            raise FontError("Font download Content-Length differs from the pinned size")
        received = 0
        while True:
            chunk = response.read1(min(CHUNK_SIZE, font.size + 1 - received))
            if not chunk:
                break
            received += len(chunk)
            if received > font.size:
                raise FontError("Font download exceeds the pinned byte limit")
            output.write(chunk)
        output.flush()
        if received != font.size:
            raise FontError("Font download ended before the pinned byte count")


# No destination-taking CLI exists. This private child only writes to stdout, which
# the parent binds directly to the original still-open temporary descriptor.
DOWNLOAD_CHILD = (
    "import runpy,sys; m=runpy.run_path(sys.argv[1]); "
    "f=next(f for f in m['load_fonts']() if f.name == sys.argv[2]); "
    "m['download_to_stream'](f, sys.stdout.buffer)"
)


def bounded_download(font, output):
    # A separate process also bounds a stalled DNS lookup or slow response headers.
    # It receives no destination path and cannot reopen/truncate a replaced temporary.
    try:
        result = subprocess.run(
            [sys.executable, "-I", "-c", DOWNLOAD_CHILD, str(Path(__file__).absolute()), font.name],
            timeout=DOWNLOAD_TIMEOUT, stdout=output, stderr=subprocess.PIPE, text=True, check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise FontError(f"{font.name}: download exceeded {DOWNLOAD_TIMEOUT} seconds") from error
    if result.returncode:
        raise FontError(f"{font.name}: download failed: {result.stderr.strip()[:1000]}")


def prepare_fonts(font_dir, fonts, offline=False, download=bounded_download):
    # Preflight every existing file and directory component before any write or network.
    font_dir = font_dir.absolute()
    directory_identity(font_dir, allow_missing=True)
    missing = []
    for font in fonts:
        target = font_dir / font.name
        if target.exists() or target.is_symlink():
            verify_font(target, font)
            print(f"Font verified: {font.name}")
        else:
            missing.append(font)
    if offline and missing:
        raise FontError("Offline mode: missing " + ", ".join(font.name for font in missing)
                        + ". Run python3 app/tools/fetch_fonts.py while online first.")
    if not missing:
        return
    font_dir.mkdir(parents=True, exist_ok=True)
    directory = directory_identity(font_dir)
    for font in missing:
        target = font_dir / font.name
        if directory_identity(font_dir) != directory:
            raise FontError("Font directory changed during preparation")
        fd, filename = tempfile.mkstemp(prefix=f".{font.name}.", suffix=".tmp", dir=font_dir)
        temp = Path(filename)
        # Retain this descriptor until publication/cleanup. Never reopen a temp path for writing.
        with os.fdopen(fd, "w+b") as output:
            try:
                check_owned_temp(temp, output)
                download(font, output)
                output.flush()
                os.fsync(output.fileno())
                if directory_identity(font_dir) != directory:
                    raise FontError("Font directory changed during preparation")
                check_owned_temp(temp, output)
                verify_stream(output, font, temp)
                # Never follow a replaced temporary symlink or overwrite a concurrent target.
                try:
                    os.link(temp, target, follow_symlinks=False)
                except FileExistsError:
                    verify_font(target, font)
                print(f"Font prepared: {font.name}")
            finally:
                # If an entry/directory was replaced, do not follow it even for cleanup.
                try:
                    if directory_identity(font_dir) == directory:
                        check_owned_temp(temp, output)
                        temp.unlink()
                except (FontError, OSError):
                    pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true", help="verify only; never use the network")
    parser.add_argument("--font-dir", type=Path, default=FONT_DIR,
                        help="resource directory (default: app/src/main/res/font)")
    args = parser.parse_args(argv)
    try:
        fonts = load_fonts()
        prepare_fonts(args.font_dir, fonts, args.offline)
        return 0
    except (FontError, OSError, ValueError) as error:
        print(f"Font preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
