# Upstream synchronization ledger

This is a partial synchronization record, not full Flutter feature or UI parity.

## Source anchors

- Flutter source: `TG-Twilight/c001apk`.
- Imported content baseline: `f309b4382b5ef12675437d584487d54e5b890eee`.
- Reviewed upstream target: `fb3e06cac5c65e9f86c902f1b4304aeee055f3e2` (upstream 1.3.5).
- Local Flutter baseline: `Ktclat/c001apk` at `1020aff898708229547fc03864b9f8a7c2d2e4f2`.
- Native baseline: `f8bbd5627db82efc3e3f77d9dc5c830a6fae7556`.

The Flutter repositories have independent Git histories. The source was imported;
there is no Git merge base. Native changes are behavior ports, not Flutter code merges.
Upstream version numbers do not replace this application's independent identity.

## This candidate

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

## Remaining target differences

- Cold/warm external Android intent delivery and sharing payload improvements.
- Public-feed HTML fallback/recovery and explicit comment loading (the app-specific
  not-listed error above is already carried).
- Markdown rendering, original-text view, and user-initiated Markdown/HTML export.
- Adaptive/expressive UI and gallery changes require native-specific review while
  retaining the custom floating dock, default scroll hiding and accessibility recovery.
- Announcement support needs a project-owned source. The upstream maintainer's
  announcement feed must not be presented as this fork's notices.
- Flutter SDK/toolchain changes are not mechanically applicable to native Compose.
  Upstream signing requirements and application identity must not replace ours.

No change in the reviewed eight upstream commits establishes a fix for the reported
native Home HTTP 403. Real provider/device evidence remains required. Synthetic
fixture results do not establish online behavior, device smoothness or full parity.
