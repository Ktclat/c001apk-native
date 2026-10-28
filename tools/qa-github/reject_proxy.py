#!/usr/bin/env python3
"""Loopback-only HTTP rejector. No forwarding, client socket, DNS, TLS or request log."""
import argparse
import json
import os
from pathlib import Path
import signal
import socket
import time

RESPONSE = (b"HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n"
            b"Content-Length: 21\r\nConnection: close\r\nX-QA-Reject-Only: 1\r\n\r\n"
            b"QA_REJECT_ONLY_LOCAL\n")
# These reserved numeric addresses are only matched in memory. Never persisted.
PROBES = {b"198.51.100.17:443": "default_https", b"198.51.100.18:443": "production_media"}


def classify(first_line):
    parts = first_line.split(b" ")
    if len(parts) == 3 and parts[0] == b"CONNECT" and parts[2] in (b"HTTP/1.0", b"HTTP/1.1"):
        return PROBES.get(parts[1], "other_connect")
    return "other_request"


def write_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")
    temporary.replace(path)


def serve(port, receipt, ready):
    counts = {key: 0 for key in ("default_https", "production_media", "other_connect", "other_request", "partial", "write_failed")}
    receipt.parent.mkdir(parents=True, exist_ok=True)
    running = True
    def stop(_number, _frame):
        nonlocal running
        running = False
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    def save(state):
        write_json(receipt, {"state": state, "pid": os.getpid(), "bind": "127.0.0.1",
            "port": port, "counts": counts, "responseStatus": 502, "forwarded": 0,
            "lastMonotonic": time.monotonic(), "scope": "Closed counters only; no destinations/headers/body retained"})
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.bind(("127.0.0.1", port))
        listener.listen(32)
        listener.settimeout(.25)
        save("ready")
        ready.write_text("ready\n")
        while running:
            try:
                connection, _peer = listener.accept()
            except socket.timeout:
                continue
            with connection:
                connection.settimeout(.5)
                line = bytearray()
                deadline = time.monotonic() + 1.0
                try:
                    # Read only a bounded request line; never retain headers or body.
                    while len(line) < 2048 and not line.endswith(b"\r\n"):
                        remaining = deadline - time.monotonic()
                        if remaining <= 0: raise TimeoutError()
                        connection.settimeout(min(.5, remaining))
                        byte = connection.recv(1)
                        if not byte:
                            break
                        line.extend(byte)
                    kind = classify(bytes(line[:-2])) if line.endswith(b"\r\n") else "partial"
                    connection.sendall(RESPONSE)
                    counts[kind] = min(9999, counts[kind] + 1)
                except (OSError, TimeoutError):
                    counts["write_failed"] = min(9999, counts["write_failed"] + 1)
                finally:
                    line.clear()
            save("ready")
        save("stopped")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--ready", type=Path, required=True)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("Only an unprivileged port is permitted")
    serve(args.port, args.receipt, args.ready)
