#!/usr/bin/env python3
"""One bounded command, not an Android full-gate/count-validation replacement."""
import argparse
import json
import os
from pathlib import Path
import sys

from native_owned_process import OwnedProcessGate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--lock", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--cwd", required=True)
    parser.add_argument("--seconds", type=float, required=True)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("A command after -- is required")
    with OwnedProcessGate(args.lock, args.evidence) as gate:
        result = gate.run("command", command, Path(args.cwd).resolve(), os.environ, args.seconds)
    print(json.dumps(result, sort_keys=True), flush=True)
    return 0 if result["status"] == "completed" and result["exitCode"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
