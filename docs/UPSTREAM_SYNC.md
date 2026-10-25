# Upstream synchronization ledger

This is a partial synchronization record, not full Flutter feature or UI parity.

## Source anchors

- Flutter source: `TG-Twilight/c001apk`.
- Imported content baseline: `f309b4382b5ef12675437d584487d54e5b890eee`.
- Reviewed first-batch target: `fb3e06cac5c65e9f86c902f1b4304aeee055f3e2` (upstream 1.3.5).
- Current audited upstream target: `851ad81cb765a183abe99c6c9e07a57cefd18c18`.
- Current native implementation foundation: `49c225942e1a9c966304902f323c884c3345bd5c`, including the guarded first-batch PR #14 integration. The foundation is now on native `main` via the separately authorized fast-forward (PR #1 closed as merged); the Markdown candidate remains isolated and unaccepted.
- Local Flutter baseline: `Ktclat/c001apk` at `1020aff898708229547fc03864b9f8a7c2d2e4f2`.
- Native baseline: `f8bbd5627db82efc3e3f77d9dc5c830a6fae7556`.

The Flutter repositories have independent Git histories. The source was imported;
there is no Git merge base. Native changes are behavior ports, not Flutter code merges.
Upstream version numbers do not replace this application's independent identity.

## Completed first-batch foundation (PR #14)

- Port the upstream exact link aliases `m.coolapk.com`, `coolapk1s.com`,
  `www.coolapk1s.com`, and the `coolmarket://com.coolapk.market` authority.
- Retain native authority, user-info and port safeguards. Decode internal components
  once with strict UTF-8 validation; reject decoded slash and backslash in identifiers.
- New parser fixtures cover existing routes, aliases, Unicode, query behavior and
  hostile/malformed inputs. No Android intent filters or exported surfaces are added.
- Port the narrowly scoped unlisted-app message: exact `v6/apk/detail`, business
  status `-2` and recognized nonexistent/not-listed wording, after existing access
  failure classification. Other endpoints, guest rules and cookies are unchanged.
- Close an existing Flutter interaction gap: My supports an aggregate pull-to-refresh
  of profile and unread counts. Independent resource retries remain independent;
  account generations, lifecycle cancellation and single-flight admission are tested.

The standalone parser diagnostic uses Kotlin 2.0.21, not the application's pinned
Kotlin 2.3.10. It establishes a bounded baseline/candidate comparison only. The
subsequent pinned Android full gate passed 902 tests in 140 suites, assembly and
lint (30 warnings, one hint, zero errors), with source hashes unchanged. See
[the executed validation record](MY_PULL_REFRESH.md) for failures, corrections,
actual scoped screenshot evidence and remaining CI/device gates. No tests were
disabled and no timeout bounds were increased.

## Current Markdown/detail candidate

The next bounded native increment adds detail-only AST rendering and the applicable
inline overflow source switch. It preserves the existing structured image/share
renderer and source/account ownership; it does not port the whole Flutter renderer
or exporter. See [the covered/deferred behavior manifest](MARKDOWN_DETAIL_PARITY.md).
The focused candidate gate compiled production/tests and passed 89 tests in seven suites; three additional actual-root frame cases passed stronger post-toggle toolbar geometry checks. The first coherent full gate assembled the APK but failed one existing preview-readiness test out of 950; lint did not run. The failure is preserved and its cause is unproven. A passing coherent gate and final independent review remain required; this is not integration approval.

## Remaining target differences

- Cold/warm external Android intent delivery and sharing payload improvements.
- Public-feed HTML fallback/recovery and explicit comment loading (the app-specific
  not-listed error above is already carried).
- Markdown/detail parity beyond the current bounded candidate, and user-initiated Markdown/HTML export.
- Automatic device identity registration in `851ad81` is intentionally not ported: it changes security/privacy and persistent identity behavior.
- Follow-success refresh (issue #12) remains deferred without an exact fixture and independent validation.
- Adaptive/expressive UI and gallery changes require native-specific review while
  retaining the custom floating dock, default scroll hiding and accessibility recovery.
- Announcement support needs a project-owned source. The upstream maintainer's
  announcement feed must not be presented as this fork's notices.
- Flutter SDK/toolchain changes are not mechanically applicable to native Compose.
  Upstream signing requirements and application identity must not replace ours.

No change in the reviewed eight upstream commits establishes a fix for the reported
native Home HTTP 403. Real provider/device evidence remains required. Synthetic
fixture results do not establish online behavior, device smoothness or full parity.
