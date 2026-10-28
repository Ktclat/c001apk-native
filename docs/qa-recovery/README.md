# QA recovery fragments (not a new approval)

Only the two files under `verified-tools/` match the recorded final tool hashes.
`prior-28-case/` is exact older published source, extracted without changing the
production checkout. It is not the lost13-case overlay.

`new-plan/ACCEPTANCE-PLAN.md` is a new plan reconstructed from requirements, not
an original test or execution receipt. All15 app cases,3 narrow repeats,6 measurement controls and2 egress preflights are NOT_RUN.
The new15th case and optional GitHub runtime orchestration are source-only; prior source CI does not constitute actual UI approval.
The original final APK, raw screenshots/XML, signing key and complete overlay are
absent. Do not synthesize them or reuse historical approval.

See `RECOVERY-MANIFEST.json` for byte-level sources and explicit rewrite gaps.
Earlier14 cases have compiled in source CI, which subsequently reported a real unit-test failure. No actual-emulator case is thereby qualified; new15th-case/orchestration sources remain uncompiled and unexecuted.
