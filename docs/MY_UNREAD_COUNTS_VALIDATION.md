# My unread-count local validation

This record supersedes only the initial verification-status paragraph in the frozen [scope plan](MY_UNREAD_COUNTS.md). Every byte of that 396-file tested source, including its initial plan, is preserved; this validation document is the only post-test addition.

## Passed local gate, not a full-suite claim

The single approved frozen run completed on 2026-10-23 UTC in approximately 11 minutes 40 seconds. Gradle, runner, and evidence-gate exit codes were all zero.

- **52 focused tests across eight XML suites** passed with zero failures, errors, or skips. Exactly 52 START and 52 END events match the named expected cases; every END is SUCCESS.
- The coverage includes strict six-field integer decoding, safe combined counts, exact no-query/no-body GET routing, account/generation and cookie-revision rejection, guest/no-session rejection, independent profile/count loading and retries, RESUMED lifecycle behavior, cancellation and stale success/failure/exception suppression, and single row accessibility semantics.
- Fresh same-run lint completed with **zero Error/Fatal issues, 27 warnings, and one hint**. Existing warnings are not represented as a clean warning-free build.
- Fourteen fresh native-rendered PNGs and source-bound metadata were archived. Six unread-focused frames cover the exact 1200 × 2670 pixel / DPR 3.25 portrait fixture, separate 140-pixel status and 179-pixel top-gesture insets, zero bottom inset, light 1× and dark 2× actual Android resource font scale, and Noto Sans. The final message row remains above the revealed dock. This is synthetic geometry and semantics coverage, not a physical-device or real TalkBack test.
- No APK assembly, installation, live login/service access, new signing material, or remote mutation was performed. The existing-key guard, offline dependencies, JDK 17, one worker, and original fixed watchdog/JVM configuration were retained. No timeout or test failure was retried or concealed. The run ended normally and the runner found no owned Gradle JVMs remaining.

## Exact identity

The unchanged input baseline is integration `fcf99a5c77345ce64e5df9876c835983cd3a40ed`, source tree `12c2fe802225441d126c51a6372a73a812bab645` (390 files). The tested candidate is content-addressed tree `fda4e8e87e68bc051cfd20d0248582c6ccb40463` (396 files), source-manifest SHA-256 `a0228ce64fc57c1f5eafefcc405e15a42a9ca4d735c81e310994112a92822a56`. All source paths, modes, and SHA-256 hashes match before and after the run.

The generated production fingerprint is `sha256:cfb242ee4dd0cfee3d62b686e87b046503ecd2a89ad5377caf96b08f1f75b3ea`, from 200 inputs under the existing `main-source-whitelist-v1` algorithm. Because validation built an exported source tree, generated Git commit, branch, and worktree state correctly say `unavailable`, not the baseline commit or a fabricated clean checkout. Each screenshot records that generated identity, the baseline identity separately, and exact dashboard/lifecycle/test hashes.

An incorrect external expected fingerprint was caught during read-only preflight and fixed to use the production algorithm's full relative-path lexical ordering. No engine had run against that incorrect value, and no production provenance algorithm or runtime behavior changed to accommodate it.

## Remaining gates

The local focused gate is not the full Android test suite. Static source enumeration predicts **871 tests across 137 app test suites** (baseline 846 across 134, plus 25 tests and three suites); actual complete CI XML must confirm those totals. Root-owned draft publication, ordinary full Android CI, APK/source/provenance/signature review, and any guarded integration remain pending.

Live service/account behavior, physical-device performance and TalkBack, and complete Flutter functional/UI parity remain unverified. The native `99+` visual cap is an intentional narrow-window readability improvement over Flutter `999+`; exact accessible counts are retained. Settings, About, dock defaults, destination routes, and mark-read behavior are unchanged.
