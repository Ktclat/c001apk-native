# QA recovery fragments (not a new approval)

Only the two files under `verified-tools/` match the recorded final tool hashes.
`prior-28-case/` is exact older published source, extracted without changing the
production checkout. It is not the lost13-case overlay.

`new-plan/ACCEPTANCE-PLAN.md` is a new plan reconstructed from requirements, not
an original test or execution receipt. All14 app cases and6 controls are NOT_RUN.
New replacements are now present under app/src/androidTest and remain uncompiled/unexecuted.
The original final APK, raw screenshots/XML, signing key and complete overlay are
absent. Do not synthesize them or reuse historical approval.

See `RECOVERY-MANIFEST.json` for byte-level sources and explicit rewrite gaps.
No build or emulator was started in this recovery stage.
