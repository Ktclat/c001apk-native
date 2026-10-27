# Independent actual-Android QA result

**APPROVED for this bounded repair increment on 2026-10-27.** The independent reviewer reopened original emulator pixels, checked execution/request/window evidence and independently hashed the final installed APK. Approval is not inferred from unit tests or old APK screenshots.

## Exact binding

| Artifact | Identity |
| --- | --- |
| Local tested production commit | `6a5acbf79d9dc4d94ab609034ea35e0478f58609` |
| Runtime whitelist fingerprint | `51d567605f8c94736edee326b0992b5f9b4f4aa43e915acd75cd0d25b9475e7a` |
| Production APK | 18,351,484 bytes; SHA256 `ea58d71b5614949f326ec84619088442f755f4884609ce62c599ee918b366134` |
| APK signer | `c5c3d971bc045bd8eef45125bb4e5594a10a7785ecb17c211b9d8f487283fd56` |
| Test-only harness commit | `c4f1430b7b30691983d500873e955a8ab7fe11d2` |
| Test APK | SHA256 `af977b183b51f21faae3ea0fb88b82087267e5be663cbe1332d16c4757e06c58` |
| Opt-in patch | `instrumentation-harness-c4f1430.patch`; SHA256 `a1b02c50afd0cbfa027074ad52454d94596f24dd40fb17e09d4d4d76db2ebad8` |

The exact production APK was installed, pulled and byte-compared before and after the run. DEX declaration inspection found 2,857 production native classes, 150 test native classes and no overlap: the test APK does not substitute production implementations. The portable 242-file `runtime-source.sha256` manifest and patch application were verified against the frozen source. A later published snapshot may have a different commit identity; source equivalence does not imply identical rebuilt APK bytes.

## Gates and executed scenarios

Full coherent build gate: **1,454 tests / 215 suites, zero failures/errors/skips**, assembly and lint passed; lint reports 34 warnings / 1 hint / zero errors.

Actual emulator: official AOSP API 26 x86_64 image, software rendering, task-only AVD, 720×1280 / 320 dpi. No Gradle ran while this final emulator was live.

| Final stage | Executed result | Seconds |
| --- | --- | ---: |
| True rotation 1, 1280×720, blacklist 2.4× | 1 passed | 56.404 |
| Unmodified MainActivity, guest, themes/modals and real SAF | 4 passed | 360.701 |
| Synthetic graphic verification → generic Retry | 4 passed | 234.022 |
| Personal rich cards and typed routes | 6 passed | 255.522 |
| Blacklist root and document-boundary cases, portrait | 13 passed + 1 intentional landscape assumption skip | 300.333 |
| Actual 640×1200 narrow rich/blacklist repeats | 2 passed | 98.809 |

**28 distinct scenarios; 30 passed invocations; one intentional assumption skip; zero failed/error invocations.** Narrow repeats do not increase distinct coverage. The landscape scenario passed separately on actual rotation 1. `runtime-scenarios.json` preserves every executed status.

## Decisive actual-UI evidence

- True landscape clear dialog now shows the complete irreversible warning initially and the complete backup sentence after scrolling, with both actions visible. Its clipped viewport is 160 px against a 98 px rendered line; full phrase glyph bounds fit. The entire body is not claimed simultaneously visible.
- MainActivity Light/Dark/System transitions and all three active appearance sheets were inspected, including open-sheet recreation and dismissal. Actual focused dialog-window flags, not only parent-window flags, agree with readable system glyph contrast.
- Actual DocumentsUI Cancel and Save used the newly observed `native-qa-users-1793111013823.json`. The exact Downloads file was pulled and parsed: 12 bytes, `["42424242"]`, SHA256 `ae8405ddd5650611aa1343128757d82bf2fba779a52e6c4632b34256c7ee221b`. Old export files remained distinct.
- Graphic verification retained the same AB12 input/challenge after positive IME observation and normal Back dismissal. Original pixels showed unobscured Submit/Cancel before successful **view-level coordinate pointer dispatch**, not OS WindowManager-injected tapping. Request ledgers establish exact retry cursor/proof consumption, old/new rows and count isolation. Cached UI-node replay is not immutable callback-replacement proof.
- Rich cards display the already-decoded media, authors, annotations, nullable counts and explicit unsupported state, with typed destinations and disabled social mutation. Six fixture ledgers are GET-only with no unexpected request.
- Blacklist cases exercise category scope, raw special-character topic navigation, exact draft/scroll retention, clear Cancel/Confirm, preservation of other data, imports/exports and stale callbacks. Synthetic document adapters are not mislabeled as real SAF; the separate production case supplies actual provider evidence.

## Scope, retained history and cleanup

All synthetic account/local stores are memory-only. Strict interceptors deny unexpected requests before dispatch; only the exact local synthetic graphic-validation POST is permitted. No real credentials, account login, CAPTCHA provider or social write is used. Guest MainActivity ordinary public reads may fail normally: Home loading and login preparation do not prove live-service success or offline boot.

Font stress is effective Compose LocalDensity 2× / 2.4×, not a changed Android system font setting. Personal Back refetches; only blacklist Back claims exact retention. No actual API 29/35 emulator, physical-device, TalkBack, performance or complete Flutter parity claim is made. Inherited gallery text contrast and a missing-author colon remain explicitly nonblocking residuals.

Earlier failed/rejected runs, including compositor lag, keyboard-covered action frames, modal contrast and landscape warning fragments, remain historical. A pre-install SystemUI ANR on this final boot was preserved; one visible Wait followed by fresh responsive drawer → Back/Home evidence preceded installation. Its cause is not claimed. None of these historical events is silently counted as a final app pass.

After all final tests, original size/density and exact prior rotation settings were restored. Installed APK comparison remained exact. Ordinary guest sync returned 0; the owned emulator stopped cleanly at **14:43:49 UTC** and released the shared lock. App crash buffer was empty; AndroidRuntime error output contained only buffer headers. Task AVDs, old files and evidence were preserved.

Task evidence is retained under `native-emulator-evidence/6a5acbf79d9d-runtime/`: original screenshots, stage logs, window/IME geometry, fixture ledgers, SAF hierarchy/readback, source/DEX/APK bindings and cleanup receipts. `original-pngs.sha256` inventories 433 raw files including cumulative unchanged pulls and boot diagnostics; this is **not** a claim of 433 distinct accepted frames.
