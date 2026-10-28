#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Reproduce the test oracle from pinned Flutter source/art, never the native catalog.

The pinned map uses ordinary single-quoted Dart strings, not raw strings. Its only
escape is backslash-dollar, whose runtime value is dollar. This deliberately
restricted extractor rejects other syntax/escapes; it is not a general Dart parser.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

COMMIT = "0d9ed002980d935c1c9351393e9f16784aa78903"
SOURCE = "apps/flutter/lib/utils/emoji_util.dart"
SOURCE_SHA256 = "5943011424fadac93d12beeab76cfdd3c694f79f716f72f3034bf8ab475632eb"
ART_MANIFEST_SHA256 = "d866a810fcb82f2325b52c30a57e9abf9c0cd99e2bcea098e751bfefdc2408b9"
# Reject unescaped interpolation dollars, raw strings and all unobserved escapes.
STRING = r"((?:[^'\\$\r\n]|\\\$)+)"
ENTRY = re.compile(r"    '" + STRING + r"': '" + STRING + r"',")
PNG = re.compile(r"(?:coolapk_emotion_|niuniu_emotion_|c_)[a-z0-9_]+\.png")
SPECIAL = {"置顶", "楼主", "层主", "ic_photo.icon"}
FIXTURE = Path(__file__).resolve().parents[1] / "src/test/resources/inline-emoticons-flutter-0d9ed002.json"


def regenerate(root):
    data = (root / SOURCE).read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError("Not the pinned Flutter emoji source")
    lines = data.decode("utf-8").splitlines()
    if lines[:2] != ["class EmojiUtil {", "  static const emojiMap = {"] or lines[-2:] != ["  };", "}"]:
        raise ValueError("Unexpected Dart map wrapper")
    entries, token_set, source_set = [], set(), set()
    png_count = total_bytes = escaped_dollars = 0
    for line in lines[2:-2]:
        match = ENTRY.fullmatch(line)
        if match is None:
            raise ValueError("Unsupported Dart map/string syntax")
        escaped_dollars += sum(value.count(r"\$") for value in match.groups())
        token, source = (value.replace(r"\$", "$") for value in match.groups())
        if token in token_set or source in source_set or not token.startswith("[") or not token.endswith("]"):
            raise ValueError("Duplicate or invalid catalog entry")
        token_set.add(token)
        source_set.add(source)
        entry = {"token": token, "source": source}
        if PNG.fullmatch(source):
            artwork = (root / "apps/flutter/assets/emojis" / source).read_bytes()
            entry["sha256"] = hashlib.sha256(artwork).hexdigest()
            png_count += 1
            total_bytes += len(artwork)
        elif source not in SPECIAL:
            raise ValueError("Unknown non-art source")
        entries.append(entry)
    if (len(entries), png_count, total_bytes, escaped_dollars) != (187, 183, 1_702_238, 6):
        raise ValueError("Pinned catalog/asset inventory differs")
    inventory = "".join(f"{e['sha256']}  {e['source']}\n" for e in sorted(entries, key=lambda e: e["source"]) if "sha256" in e)
    if hashlib.sha256(inventory.encode("utf-8")).hexdigest() != ART_MANIFEST_SHA256:
        raise ValueError("Artwork differs from pinned original PNG hashes")
    result = dict(source_commit=COMMIT, source_file=SOURCE, source_sha256=SOURCE_SHA256, entries=entries)
    return (json.dumps(result, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("flutter_checkout", type=Path)
    parser.add_argument("--output", type=Path, help="Write regenerated oracle; otherwise check the committed fixture")
    args = parser.parse_args()
    expected = regenerate(args.flutter_checkout)
    if args.output:
        args.output.write_bytes(expected)
    elif FIXTURE.read_bytes() != expected:
        raise SystemExit("Fixture differs from independently decoded Flutter source/artwork")
    print("187 decoded pairs / 183 PNGs / 1702238 bytes; fixture SHA256 " + hashlib.sha256(expected).hexdigest())


if __name__ == "__main__":
    main()
