# Rewritten owned-process supervisor — 2026-10-28

These are **new source files**, not restoration of the missing Oct27 originals.
The available task context preserved design notes and historical SHA-256 values,
but not the original creation commands or complete source. Old review approval,
old synthetic passes and old Android results do not approve these replacements.

Historical identities (not expected to match this rewrite):

- old core: `eeb1e2a30faf94b3891d54bc9bb09b95d7a6a59b6390f1af98cc0623c8dbdd9a`
- old tests: `beb7d4314a2b51da15d54e10663f03e4a6a5b76464fc2756e8aeba8739bb8e1c`
- old full diagnostic runner: `705816c1c2d0f548051a7e81c5cb82150f20f386ec7afd2dcfee642f5ed67fc6`

## Restricted API

Linux Python 3 with pidfd support is required. Run the supervisor in a **dedicated
single-threaded process with no pre-existing children**. It refuses other hosts.
During an armed invocation, all caller-created subprocesses must belong to that
command or its bounded evidence capture. Do not create unrelated threads or
children in callbacks. This is not a generic background-job manager, security
sandbox, Android emulator manager, or replacement for full gate validation.

```python
with OwnedProcessGate(lock_path, evidence_dir) as gate:
    result = gate.run(name, argv, cwd, env, timeout_seconds,
                      stop_condition=None, before_stop=None)
```

The phase directory must be new. Each attempt retains output, start/terminal
receipts, exact owned PID/startTicks identities and cleanup signals. Environment
variables are not dumped. Do not put secrets in command arguments. The optional
stop callback returns a reason only after its failure evidence is durable; the
pre-stop callback receives verified owned identities and has a separate 15-second
ceiling. Neither callback changes application/test assertions or timeouts.

Ownership is armed before `Popen`. Linux subreaper adoption and fresh parent
identity checks capture detached descendants without claiming a process merely
by a reused PID/session/group number. Signals use retained pidfds only. Both
successful and interrupted commands must prove all owned descendants exited and
were reaped, with two stable empty scans and all-thread child-list checks.
Normal root exit zero followed by forced descendant cleanup is explicitly not
`completed`. Full-gate callers must require `status == "completed"`, exit zero,
no cleanup signals, no remaining children, then independently validate test
events/XML, source binding and artifacts. A supervisor pass is not an app pass.

Work has a finite deadline. Cleanup uses bounded INT, TERM, KILL stages. A denied
signal is not retried through another API. **If cleanup cannot be proved, the
supervisor reports `blocked-cleanup-lock-held`, retains the lock, and continues
read-only observation rather than exiting.** This safety state intentionally has
no automatic time limit. Escalation or external termination needs explicit
operator handling. SIGKILL of the supervisor cannot be intercepted; an unresolved
`<lock>.lease.json` therefore rejects future invocations even if flock is free.
Never delete that lease or bypass it based only on an available lock. Borrowed
lock descriptors passed to children must never be explicitly unlocked by them.

`run_owned.py` is a minimal one-command wrapper. It is **not** the lost full-order
diagnostic runner and does not reconstruct its event collector, source freeze,
test-order validation, APK audit, or acceptance evidence. A real Android gate
needs its own new review and explicit parent grant; no Gradle/AVD is run by the
synthetic tests below.

## Limited synthetic validation

After independent review grants execution of the exact new source hashes:

```bash
export OWNED_TEST_EVIDENCE=/absolute/path/to/new-synthetic-evidence
python3 -m unittest -v test_native_owned_process.py
```

The finite fake-child cases cover normal completion/borrowed lock, constructor
failure after spawn, missing executable, monitor/evidence exception, deadline
with detached descendant, first-failure marker, root-zero orphan/non-green,
logical PID reuse/unrelated negative, post-exit adoption race, unresolved lease,
and denied-signal observation. Each real lifecycle case has an unrelated sibling
sentinel. Its harness deadline does **not** kill an uncertain owning supervisor;
it stops the suite and reports the retained PID/evidence instead. Logical mocks
are not tests of actual kernel PID exhaustion or permission-denied cleanup.

Publish source only through the parent-owned authorized test branch. Do not
commit generated logs, process receipts, toolchain binaries or credentials.
