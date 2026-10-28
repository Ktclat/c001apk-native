#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Offline closed-inventory checks for original Flutter artwork in source and APK bytes."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import zipfile

MANIFEST_SHA256 = "d866a810fcb82f2325b52c30a57e9abf9c0cd99e2bcea098e751bfefdc2408b9"
COUNT = 183
TOTAL_BYTES = 1_702_238
DIRECTORY = Path("app/src/main/res/drawable-nodpi")
MANIFEST = Path("app/tools/emoticons.sha256")
RECORD = re.compile(r"([0-9a-f]{64})  ((?:coolapk_emotion_|niuniu_emotion_|c_)[a-z0-9_]+\.png)")


def absolute(path):
    return Path(os.path.abspath(path))  # Do not erase symlink evidence with resolve().


def no_symlinks(path):
    for component in (path, *path.parents):
        if component.is_symlink():
            raise ValueError("Symlink in artwork input")


def artwork_name(name):
    return name.startswith(("coolapk_emotion_", "niuniu_emotion_", "c_"))


def read_manifest(manifest):
    manifest = absolute(manifest)
    no_symlinks(manifest)
    if not manifest.is_file() or manifest.stat().st_size > 65536:
        raise ValueError("Missing or oversized original artwork manifest")
    raw = manifest.read_bytes()
    if hashlib.sha256(raw).hexdigest() != MANIFEST_SHA256:
        raise ValueError("Manifest does not match pinned Flutter inventory")
    expected = {}
    for line in raw.decode("utf-8").splitlines():
        match = RECORD.fullmatch(line)
        if not match or match[2] in expected:
            raise ValueError("Invalid or duplicate emoticon record")
        expected[match[2]] = match[1]
    if len(expected) != COUNT:
        raise ValueError("Incomplete Flutter inventory")
    return expected


def verify_source(root, expected):
    root = absolute(root)
    directory = root / DIRECTORY
    no_symlinks(directory)
    if not directory.is_dir() or {p.name for p in directory.iterdir()} != expected.keys():
        raise ValueError("Source artwork directory differs from exact inventory")
    for candidate in (root / "app/src/main/res").rglob("*"):
        if candidate.is_symlink():
            raise ValueError("Symlink in resource inventory")
        if artwork_name(candidate.name) and (candidate.parent != directory or candidate.name not in expected):
            raise ValueError("Unlisted or qualified artwork alias")
    total = 0
    for name, digest in expected.items():
        path = directory / name
        no_symlinks(path)
        if not path.is_file() or not 0 < path.stat().st_size <= TOTAL_BYTES - total:
            raise ValueError("Invalid original artwork byte budget")
        data = path.read_bytes()
        total += len(data)
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError("Source artwork differs from original Flutter bytes: " + name)
    if total != TOTAL_BYTES:
        raise ValueError("Incomplete original artwork bytes")


def verify_apk(apk, expected):
    with zipfile.ZipFile(apk) as bundle:
        entries = [entry for entry in bundle.infolist()
                   if entry.filename.startswith("res/drawable") and artwork_name(Path(entry.filename).name)]
        actual = {}
        for entry in entries:
            path = Path(entry.filename)
            if path.name in actual:
                raise ValueError("Duplicate APK artwork basename or qualifier")
            if path.parent.as_posix() not in ("res/drawable-nodpi", "res/drawable-nodpi-v4"):
                raise ValueError("APK artwork is not an unscaled nodpi resource")
            actual[path.name] = entry
        if actual.keys() != expected.keys():
            raise ValueError("APK artwork inventory is missing or has extra names")
        total = 0
        for name, digest in expected.items():
            entry = actual[name]
            if not 0 < entry.file_size <= TOTAL_BYTES - total:
                raise ValueError("APK artwork byte budget exceeded")
            data = bundle.read(entry)
            total += len(data)
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError("APK artwork differs from original Flutter bytes: " + name)
        if total != TOTAL_BYTES:
            raise ValueError("APK artwork size differs from source inventory")


def verify(apk, source_root):
    expected = read_manifest(source_root / MANIFEST)
    verify_source(source_root, expected)
    verify_apk(apk, expected)
    print(f"PASS: {COUNT} byte-exact Flutter emoticons, {TOTAL_BYTES} bytes in source and {apk.name}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).absolute().parents[2])
    args = parser.parse_args()
    verify(args.apk, args.source_root)
