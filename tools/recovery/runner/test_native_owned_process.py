"""Finite fake-child tests only; not Gradle, Android, or application tests.

Every lifecycle case runs in its own dedicated supervisor. An unrelated sibling
sentinel belongs to the test harness and must survive. A harness deadline NEVER
kills a possibly-owning supervisor: it reports its PID and stops the suite.
"""
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

import native_owned_process as core


HERE = Path(__file__).resolve().parent


def check_receipt(root, phase):
    value = json.loads((root / "evidence" / phase / "receipt.json").read_text())
    assert value["cleanup"]["remaining"] == []
    assert all(row["exited"] for row in value["ownedIdentities"])
    assert not core.direct_children()
    return value


def lifecycle_case(case, root):
    lock = root / "heavy.lock"
    evidence = root / "evidence"
    ready = root / "ready"
    command = [sys.executable, "-c", "import time; time.sleep(60)"]
    gate = core.OwnedProcessGate(lock, evidence)
    if case == "normal":
        child = """import os,fcntl
fd=int(os.environ['NATIVE_BORROWED_LOCK_FD']); os.fstat(fd)
other=os.open('heavy.lock',os.O_RDWR)
try:
 fcntl.flock(other,fcntl.LOCK_EX|fcntl.LOCK_NB)
 raise AssertionError('borrowed lock is not held')
except BlockingIOError: pass
print('child-normal')
"""
        with gate:
            result = gate.run(case, [sys.executable, "-c", child], root, os.environ, 3)
        assert result["status"] == "completed" and not result["cleanup"]["signals"]
    elif case == "spawnfail":
        real_popen = subprocess.Popen

        def partial_spawn(*args, **kwargs):
            real_popen(*args, **kwargs)
            raise OSError("synthetic constructor failure AFTER spawn")

        try:
            with gate:
                with mock.patch.object(core.subprocess, "Popen", side_effect=partial_spawn):
                    gate.run(case, command, root, os.environ, 3)
        except OSError as error:
            assert "AFTER spawn" in str(error)
        else:
            raise AssertionError("spawn exception swallowed")
        assert check_receipt(root, case)["ownedIdentities"]
    elif case == "missingexec":
        try:
            with gate:
                gate.run(case, [str(root / "nonexistent-executable")], root, os.environ, 3)
        except FileNotFoundError:
            pass
        else:
            raise AssertionError("invalid executable accepted")
        assert not check_receipt(root, case)["ownedIdentities"]
    elif case == "monitorraise":
        def stop():
            raise ValueError("synthetic original monitor error")

        def evidence_error(_reason, _rows):
            raise LookupError("synthetic evidence failure")

        try:
            with gate:
                gate.run(case, command, root, os.environ, 3, stop, evidence_error)
        except ValueError as error:
            assert str(error) == "synthetic original monitor error"
        else:
            raise AssertionError("original monitor exception replaced/swallowed")
        value = check_receipt(root, case)
        assert value["status"] == "evidence-error" and "LookupError" in value["beforeStopError"]
    elif case == "timeout":
        child = """import os,signal,time
if os.fork()==0:
 os.setsid()
 signal.signal(signal.SIGINT,signal.SIG_IGN)
 open('ready','w').write(str(os.getpid()))
time.sleep(60)
"""
        with gate:
            result = gate.run(case, [sys.executable, "-c", child], root, os.environ, 0.5)
        assert ready.exists() and result["status"] == "timeout"
        assert len(result["ownedIdentities"]) >= 2 and result["cleanup"]["signals"]
    elif case == "marker":
        child = "import pathlib,time; pathlib.Path('ready').write_text('failure captured'); time.sleep(60)"

        def stop():
            return "first-failure-captured" if ready.exists() else None

        def before_stop(reason, rows):
            assert reason == "first-failure-captured" and any(not row["exited"] for row in rows)
            core.write_json(root / "before-stop.json", {"rows": rows, "marker": ready.read_text()})

        with gate:
            result = gate.run(case, [sys.executable, "-c", child], root, os.environ, 3, stop, before_stop)
        assert result["status"] == "stopped" and (root / "before-stop.json").exists()
    elif case == "orphan":
        child = """import os,signal,time
r,w=os.pipe()
if os.fork()==0:
 os.close(r); os.setsid(); signal.signal(signal.SIGINT,signal.SIG_IGN)
 os.write(w,b'x'); os.close(w); time.sleep(60)
else:
 os.close(w); os.read(r,1); os._exit(0)
"""
        with gate:
            result = gate.run(case, [sys.executable, "-c", child], root, os.environ, 3)
        assert result["exitCode"] == 0 and result["status"] == "completed-forced-owned-cleanup"
    else:
        raise AssertionError(case)
    check_receipt(root, case)
    assert json.loads(gate.lease_path.read_text())["state"] == "clean"
    with open(lock, "a") as probe:
        fcntl.flock(probe, fcntl.LOCK_EX | fcntl.LOCK_NB)
    print(json.dumps({"case": case, "result": "PASS", "evidence": str(evidence)}), flush=True)


class LifecycleTests(unittest.TestCase):
    def run_case(self, name):
        output_root = Path(os.environ.get("OWNED_TEST_EVIDENCE", tempfile.gettempdir()))
        output_root.mkdir(parents=True, exist_ok=True)
        root = Path(tempfile.mkdtemp(prefix=f"owned-{name}-", dir=output_root))
        sentinel = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        sentinel_fd = os.pidfd_open(sentinel.pid)
        try:
            with open(root / "supervisor.log", "wb") as log:
                proc = subprocess.Popen([sys.executable, str(Path(__file__).resolve()),
                                         "--case", name, str(root)], stdout=log, stderr=subprocess.STDOUT)
                try:
                    result = proc.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    # No kill/retry: a defective supervisor may still own fake
                    # descendants. Preserve it and its lock for diagnosis.
                    # unittest propagates KeyboardInterrupt, but would swallow
                    # SystemExit as a per-test error and continue other cases.
                    raise KeyboardInterrupt(f"BLOCKED fake supervisor PID {proc.pid}; retained at {root}")
            self.assertEqual(result, 0, (root / "supervisor.log").read_text())
            self.assertIsNone(sentinel.poll(), "Unrelated sibling was signaled")
            print(f"{name}: {root}", flush=True)
        finally:
            if not core.exited(sentinel_fd):
                signal.pidfd_send_signal(sentinel_fd, signal.SIGTERM)
            sentinel.wait(timeout=3)
            os.close(sentinel_fd)

    def test_normal(self): self.run_case("normal")
    def test_constructor_exception_after_child_created(self): self.run_case("spawnfail")
    def test_missing_executable(self): self.run_case("missingexec")
    def test_monitor_and_evidence_exception(self): self.run_case("monitorraise")
    def test_timeout_with_detached_descendant(self): self.run_case("timeout")
    def test_first_marker_before_stop(self): self.run_case("marker")
    def test_root_zero_with_orphan_is_not_green(self): self.run_case("orphan")

    def test_pid_reuse_owned_positive_and_unrelated_negative(self):
        gate = core.OwnedProcessGate("unused", "unused")
        gate.known[(43210, 10)] = 101
        owned = {"pid": 43210, "startTicks": 20, "ppid": os.getpid(), "state": "S"}
        with mock.patch.object(core, "proc_stat", return_value=owned), \
             mock.patch.object(core, "exited", return_value=True), \
             mock.patch.object(core.os, "pidfd_open", return_value=102) as pin:
            self.assertTrue(gate._pin(owned))
            pin.assert_called_once_with(43210)
        unrelated = {**owned, "startTicks": 30, "ppid": 1}
        with mock.patch.object(core, "proc_stat", return_value=unrelated), \
             mock.patch.object(core.os, "pidfd_open") as pin:
            self.assertFalse(gate._pin(unrelated))
            pin.assert_not_called()
        with mock.patch.object(core, "proc_stat", return_value={**owned, "startTicks": 40}), \
             mock.patch.object(core, "exited", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "old pidfd is live"):
                gate._pin({**owned, "startTicks": 40})

    def test_empty_scan_rechecks_post_exit_adoption(self):
        gate = core.OwnedProcessGate("unused", "unused")
        count = 0

        def capture():
            nonlocal count
            count += 1
            if count == 2:
                gate.known[(123, 99)] = 101  # newborn absent from first table

        with mock.patch.object(gate, "_capture", side_effect=capture), \
             mock.patch.object(gate, "_reap"), \
             mock.patch.object(core, "exited", return_value=False), \
             mock.patch.object(core, "direct_children", return_value=set()):
            self.assertFalse(gate._empty_once())
            self.assertEqual(count, 2)
        with mock.patch.object(gate, "_empty_once", side_effect=[True, False]), \
             mock.patch.object(core.time, "sleep"):
            self.assertFalse(gate._prove_empty())

    def test_unresolved_lease_refuses_new_run(self):
        with tempfile.TemporaryDirectory() as temp:
            gate = core.OwnedProcessGate(Path(temp) / "lock", Path(temp) / "evidence")
            core.write_json(gate.lease_path, {"state": "owning"})
            with self.assertRaisesRegex(RuntimeError, "Unresolved ownership"):
                gate.__enter__()
            self.assertIsNone(gate.lock_fd)

    def test_denied_signal_observes_only_until_exit_proof(self):
        with tempfile.TemporaryDirectory() as temp:
            gate = core.OwnedProcessGate(Path(temp) / "lock", temp)
            with mock.patch.object(gate, "_prove_empty", side_effect=[False, False, True]), \
                 mock.patch.object(gate, "_signal_owned", side_effect=PermissionError("synthetic denial")) as send, \
                 mock.patch.object(core.time, "monotonic", side_effect=range(100)), \
                 mock.patch.object(gate, "_lease") as lease:
                receipt = gate._cleanup(Path(temp))
            send.assert_called_once()
            lease.assert_called_once_with("blocked-cleanup-lock-held")
            self.assertEqual(receipt["proof"], "two-empty-scans-after-blocked")
            self.assertIn("synthetic denial", receipt["problem"])


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--case":
        lifecycle_case(sys.argv[2], Path(sys.argv[3]))
    else:
        unittest.main()
