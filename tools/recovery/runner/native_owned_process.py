"""New recovery implementation; not the lost, historically approved supervisor.

Linux only. Use in a dedicated single-threaded process with no existing children.
Callbacks may create only this run's evidence helpers. No other concurrent forks.
Work is bounded; an unprovable cleanup deliberately holds the lock indefinitely.
"""
from __future__ import annotations

import ctypes
import fcntl
import json
import math
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import time
import traceback


def stamp():
    return {"utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "monotonic": time.monotonic()}


def write_json(path, value):
    path = Path(path)
    temp = path.with_name(path.name + ".tmp")
    with open(temp, "w", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)
    fd = os.open(path.parent, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def proc_stat(pid):
    try:
        value = Path(f"/proc/{pid}/stat").read_text()
    except (FileNotFoundError, ProcessLookupError):
        return None
    fields = value.rsplit(")", 1)[1].split()
    return {"pid": int(pid), "state": fields[0], "ppid": int(fields[1]),
            "startTicks": int(fields[19])}


def proc_table():
    result = {}
    for path in Path("/proc").iterdir():
        if path.name.isdigit():
            row = proc_stat(int(path.name))
            if row is not None:
                result[row["pid"]] = row
    return result


def direct_children():
    result = set()
    for path in Path("/proc/self/task").glob("*/children"):
        try:
            result.update(map(int, path.read_text().split()))
        except FileNotFoundError:
            # A disappearing thread cannot fork again. Another full scan follows.
            continue
    return result


def exited(fd):
    return bool(select.select([fd], [], [], 0)[0])


class Deadline(BaseException):
    pass


class Interrupted(BaseException):
    pass


class OwnedProcessGate:
    """Own an exclusive gate and only children created during its armed run.

    A lease beside the lock is a persistent fail-closed guard if this supervisor
    itself is killed. Do not bypass an unresolved lease merely because flock is
    available. This is not a cgroup, a hostile-process sandbox, or AVD management.
    """

    def __init__(self, lock_path, evidence_dir):
        self.lock_path = Path(lock_path).resolve()
        self.lease_path = self.lock_path.with_name(self.lock_path.name + ".lease.json")
        self.evidence_dir = Path(evidence_dir).resolve()
        self.lock_fd = None
        self.known = {}  # (PID, startTicks) -> pidfd; retains old identities.
        self.proc = None
        self.root_key = None
        self.armed = False
        self.clean = True
        self.old_subreaper = None
        self.libc = ctypes.CDLL(None, use_errno=True)

    def _prctl(self, option, value):
        if self.libc.prctl(option, value, 0, 0, 0) != 0:
            raise OSError(ctypes.get_errno(), "prctl failed")

    def _lease(self, state):
        write_json(self.lease_path, {"state": state, "supervisor": proc_stat(os.getpid()),
                                   "evidence": str(self.evidence_dir), **stamp()})

    def __enter__(self):
        if len(list(Path("/proc/self/task").iterdir())) != 1 or direct_children():
            raise RuntimeError("Dedicated single-threaded, child-free supervisor required")
        if not hasattr(os, "pidfd_open") or not hasattr(signal, "pidfd_send_signal"):
            raise RuntimeError("Linux pidfd support required")
        self.evidence_dir.mkdir(parents=True, exist_ok=True)
        self.lock_path.parent.mkdir(parents=True, exist_ok=True)
        self.lock_fd = os.open(self.lock_path, os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if self.lease_path.exists():
                lease = json.loads(self.lease_path.read_text())
                if lease.get("state") != "clean":
                    raise RuntimeError("Unresolved ownership lease; manual evidence review required")
            previous = ctypes.c_int()
            self._prctl(37, ctypes.byref(previous))  # PR_GET_CHILD_SUBREAPER
            self.old_subreaper = previous.value
            self._prctl(36, 1)  # PR_SET_CHILD_SUBREAPER
            self._lease("locked-no-child")
            return self
        except BaseException:
            if self.old_subreaper is not None:
                self._prctl(36, self.old_subreaper)
            os.close(self.lock_fd)
            self.lock_fd = None
            raise

    def _belongs(self, row):
        if row["ppid"] == os.getpid():
            return True
        for (pid, start), fd in self.known.items():
            if pid == row["ppid"] and not exited(fd):
                parent = proc_stat(pid)
                if parent is not None and parent["startTicks"] == start:
                    return True
        return False

    def _pin(self, row):
        key = (row["pid"], row["startTicks"])
        if key in self.known:
            return False
        before = proc_stat(key[0])
        if before is None or before["startTicks"] != key[1] or not self._belongs(before):
            return False
        for old_key, fd in self.known.items():
            if old_key[0] == key[0] and not exited(fd):
                raise RuntimeError("PID identity changed while old pidfd is live")
        try:
            fd = os.pidfd_open(key[0])
        except ProcessLookupError:
            return False
        after = proc_stat(key[0])
        if after is None or after["startTicks"] != key[1] or not self._belongs(after):
            os.close(fd)
            return False
        self.known[key] = fd
        return True

    def _capture(self):
        table = proc_table()
        # The child-free entry condition and single-threaded dedicated caller
        # make every newly adopted direct child part of this armed invocation.
        while True:
            added = False
            for row in table.values():
                # Both the child's identity and a currently owned parent are
                # checked again around pidfd_open; stale table PPIDs alone do
                # not claim a descendant after an intermediate PID was reused.
                added = self._pin(row) or added
            if not added:
                break

    def _rows(self):
        return [{"pid": pid, "startTicks": start, "exited": exited(fd)}
                for (pid, start), fd in sorted(self.known.items())]

    def _reap(self):
        if self.proc is not None:
            self.proc.poll()  # Popen alone reaps its root, preserving its exit code.
        for (pid, start), fd in list(self.known.items()):
            if (pid, start) == self.root_key:
                continue
            row = proc_stat(pid)
            if row is not None and row["startTicks"] == start and row["state"] == "Z":
                try:
                    os.waitpid(pid, os.WNOHANG)
                except ChildProcessError:
                    pass  # Still a grandchild; its parent/subreaper will reap it.

    def _empty_once(self):
        self._capture()
        self._reap()
        # Re-scan after exits/reaping: a just-exited parent may have forked after
        # the first table read. Check all thread child lists as well as pidfds.
        self._capture()
        self._reap()
        return not any(not exited(fd) for fd in self.known.values()) and not direct_children()

    def _prove_empty(self):
        if not self._empty_once():
            return False
        time.sleep(0.05)
        return self._empty_once()

    def _signal_owned(self, sig, signals):
        self._capture()
        for (pid, start), fd in list(self.known.items()):
            if not exited(fd):
                try:
                    signal.pidfd_send_signal(fd, sig)
                except ProcessLookupError:
                    continue
                # Permission/unknown failures propagate; never retry a denied
                # signal or switch to PID/pattern-wide kill as a workaround.
                signals.append({"pid": pid, "startTicks": start,
                                "signal": signal.Signals(sig).name, **stamp()})

    def _cleanup(self, directory):
        signals = []
        problem = None
        try:
            for sig, seconds in [(None, 0.25), (signal.SIGINT, 2),
                                 (signal.SIGTERM, 2), (signal.SIGKILL, 2)]:
                if self._prove_empty():
                    return {"signals": signals, "remaining": [], "proof": "two-empty-scans"}
                if sig is not None:
                    self._signal_owned(sig, signals)
                until = time.monotonic() + seconds
                while time.monotonic() < until:
                    if self._prove_empty():
                        return {"signals": signals, "remaining": [], "proof": "two-empty-scans"}
                    time.sleep(0.05)
            problem = "Owned children remain after bounded cleanup"
        except BaseException:
            problem = traceback.format_exc()
        # Do not return/raise out through __exit__, releasing ownership. A
        # surviving supervisor remains a lock holder; only read-only observation
        # continues after a denied/uncertain cleanup. SIGKILL cannot be handled,
        # hence the unresolved persistent lease also blocks the next invocation.
        blocked = {"state": "blocked-cleanup-lock-held", "problem": problem,
                   "signals": signals, **stamp()}
        print(json.dumps(blocked), file=sys.stderr, flush=True)
        try:
            write_json(directory / "blocked-cleanup.json", blocked)
            self._lease("blocked-cleanup-lock-held")
        except BaseException:
            traceback.print_exc()
        while True:
            try:
                if self._prove_empty():
                    return {"signals": signals, "remaining": [],
                            "proof": "two-empty-scans-after-blocked", "problem": problem}
                time.sleep(1)
            except BaseException:
                pass

    def run(self, name, command, cwd, env, timeout_seconds,
            stop_condition=None, before_stop=None):
        if (self.lock_fd is None or self.armed or direct_children()
                or len(list(Path("/proc/self/task").iterdir())) != 1):
            raise RuntimeError("Gate not ready or unexpected existing child")
        if not name or Path(name).name != name or name in (".", ".."):
            raise ValueError("Phase name must be one path component")
        if not math.isfinite(timeout_seconds) or timeout_seconds <= 0:
            raise ValueError("Positive finite work deadline required")
        if signal.getitimer(signal.ITIMER_REAL) != (0.0, 0.0):
            raise RuntimeError("An existing real-time alarm is not supported")
        directory = self.evidence_dir / name
        directory.mkdir()  # Never overwrite a prior attempt.
        receipt = {"name": name, "command": list(command), "cwd": str(cwd),
                   "timeoutSeconds": timeout_seconds, "started": stamp()}
        self.proc = None
        self.root_key = None
        handlers = {sig: signal.getsignal(sig) for sig in (signal.SIGALRM, signal.SIGINT, signal.SIGTERM)}

        def alarm(_sig, _frame):
            raise Deadline()

        def interrupt(sig, _frame):
            raise Interrupted(signal.Signals(sig).name)

        signal.signal(signal.SIGALRM, alarm)
        signal.signal(signal.SIGINT, interrupt)
        signal.signal(signal.SIGTERM, interrupt)
        reason = None
        caught = None
        cleanup = None
        try:
            # Arm before any Popen work: even a constructor exception can leave
            # a newly created child, discoverable as a direct/adopted child.
            self.armed = True
            self.clean = False
            self._lease("owning")
            write_json(directory / "started.json", receipt)
            child_env = dict(env)
            child_env["NATIVE_BORROWED_LOCK_FD"] = str(self.lock_fd)
            child_env["NATIVE_GATE_SUPERVISOR_PID"] = str(os.getpid())
            signal.setitimer(signal.ITIMER_REAL, timeout_seconds)
            with open(directory / "output.log", "xb", buffering=0) as output:
                self.proc = subprocess.Popen(list(command), cwd=cwd, env=child_env,
                                             stdout=output, stderr=subprocess.STDOUT,
                                             start_new_session=True, pass_fds=(self.lock_fd,))
                root_row = proc_stat(self.proc.pid)
                if root_row is None:
                    raise RuntimeError("New root identity unavailable before first poll")
                self.root_key = (root_row["pid"], root_row["startTicks"])
                while True:
                    self._capture()
                    if self.proc.poll() is not None:
                        break
                    if stop_condition is not None:
                        reason = stop_condition()
                        if reason:
                            break
                    time.sleep(0.05)
        except Deadline:
            reason = "deadline"
        except BaseException as error:
            caught = error
            reason = "exception"
            receipt["exception"] = traceback.format_exc()
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            # Cleanup cannot be skipped by a second Ctrl-C/TERM. SIGKILL is an
            # explicit external failure, protected only by the persistent lease.
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            try:
                if reason and before_stop is not None:
                    try:
                        self._capture()
                        signal.setitimer(signal.ITIMER_REAL, 15)
                        before_stop(reason, self._rows())
                    except BaseException:
                        receipt["beforeStopError"] = traceback.format_exc()
                    finally:
                        signal.setitimer(signal.ITIMER_REAL, 0)
                cleanup = self._cleanup(directory)
                self.clean = True
                self.armed = False
            finally:
                signal.setitimer(signal.ITIMER_REAL, 0)
                for sig, handler in handlers.items():
                    signal.signal(sig, handler)
        receipt.update({"exitCode": self.proc.returncode if self.proc is not None else None,
                        "stopReason": reason, "cleanup": cleanup,
                        "ownedIdentities": self._rows(), "finished": stamp()})
        if reason:
            status = "timeout" if reason == "deadline" else "stopped" if caught is None else "exception"
        elif receipt["exitCode"] != 0:
            status = "failed"
        elif cleanup["signals"] or cleanup.get("problem"):
            status = "completed-forced-owned-cleanup"
        else:
            status = "completed"
        if "beforeStopError" in receipt:
            status = "evidence-error"
        receipt["status"] = status
        try:
            write_json(directory / "receipt.json", receipt)
        finally:
            for fd in self.known.values():
                os.close(fd)
            self.known.clear()
        if caught is not None:
            raise caught
        return receipt

    def __exit__(self, kind, value, tb):
        # Only the run's proven-empty boundary may mark clean. Recheck before
        # handoff; unexpected caller forks are unsafe, not silently abandoned.
        if not self.clean or self.armed or direct_children():
            handlers = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
            signal.setitimer(signal.ITIMER_REAL, 0)
            for sig in handlers:
                signal.signal(sig, signal.SIG_IGN)
            try:
                self._cleanup(self.evidence_dir)
            finally:
                for sig, handler in handlers.items():
                    signal.signal(sig, handler)
        self._lease("clean")
        self._prctl(36, self.old_subreaper)
        os.close(self.lock_fd)
        self.lock_fd = None
        return False
