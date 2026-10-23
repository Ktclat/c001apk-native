# Native image composer: bounded validation, full CI pending

Base: `50e7655e557e0a696a8692086e9d8582d73d599e`. This candidate adds native feed/reply image composing and related UI/lifecycle tests. It is not a declaration of full Flutter parity, live-service acceptance, or readiness to merge into main.

## Implemented scope

- Home publish and feed reply routes use a retained, account-generation/route-owned composer. Draft text, ordered image selection, audience/reply-forward choice and pending confirmation survive configuration recreation; actual route/account disposal cancels ownership and releases private staged files. Confirmed completion returns to the originating surface and refreshes that origin once. Unknown delivery remains in the composer without automatic resubmission.
- Selection stages bounded content-URI bytes off Main, without uploading. Up to nine images, 20 MiB per image and 60 MiB per batch are supported. Additional native dimension/decoded-pixel and raster-format limits are defensive client limits, not asserted service limits. The 500-grapheme limit follows the Flutter composer; a separate 32,768 UTF-16-unit input bound prevents pathological pastes.
- Explicit confirmation freezes the destination, generation, text, privacy choice and image order. It explains that originals can contain location metadata and that self-only feed visibility does not make an uploaded image URL private.
- All upload grants are validated before sequential PUTs. Exact names, hashes, destination restrictions and unique object/publication URLs are checked. Missing/changed staged files fail closed. Partial or uncertain PUT stops the remainder and prevents publication. Uncertain final publication is terminal. An explicit rejected CAPTCHA can resume the exact final operation once after verification, without uploading the same images again. Cancellation does not promise deletion of remote orphan images.
- Preview geometry preserves normal portrait/landscape originals rather than square-cropping. Extreme-aspect images use explicit dimensions/type placeholders, not a misleading recognizable preview. Removal controls have separate 48 dp targets. The confirmation window preserves the composer's effective density, including the app font multiplier, exactly once.

## Offline evidence and corrections

The native/Flutter comparison uses the same two synthetic, labeled PNG originals, not user photos. Their SHA-256 values are pinned in `app/src/test/resources/composer-reference/fixture.json`; no font binaries or credentials are added.

Build95 passed its earlier 170-test ordered focus but did not prove larger dialog text: the old recorded scale was only the requested outer value. Subsequent measurements exposed the separate Dialog window dropping the app's effective density. The bounded production correction restores that effective density inside the confirmation slots; it does not multiply the scale again or alter other dialogs.

Build96/97's generic text overflow failures were investigated rather than ignored. In Compose 1.7.6, String Text's semantics reconstructs MultiParagraph using parent constraints while retaining the actual narrow text layout size. Therefore its generic width-overflow flag is not a reliable clipping witness for these slots. Tests now require complete contiguous text coverage, each line's actual bounds, no ellipsis, no max-line truncation or height overflow, measured effective density, unclipped title/actions, contained 48 dp action targets and warning endpoint reachability. Four-slot numeric evidence is written before assertions so a failed capture remains diagnosable.

Build98's ten presentation cases passed. Independent review inspected all six unique dialog images across fourteen top/tail captures and all 28 measured text-slot ink crops. Actual title ink changed from 345×72 pixels at effective 1.0 to 415×87 at 1.5600001 and 424×89 at 1.6. Warning and action text fit the compact, landscape and after injected-IME fixture windows. Every top/tail pair was identical because the text fit; these images do not demonstrate distinct scrolling.

The CAPTCHA fixture also required a separate correction. Compose 1.7.6's test continuation interceptor was observed alongside Dispatchers.IO and could retain execution on the test UI scheduler. The test now uses the supported caller-owned retry callback with a properly disposed real-Main scope, matching the production composer's ViewModel-owned architecture. It does not validate MutationStatus's default rememberCoroutineScope branch. Actual Thread identity and the real IO interceptor are asserted, as are Main-thread acknowledgement/UI composition, visible confirmation and exactly-one replay/callback. The same five-second visibility condition always queries actual UI semantics before the model condition. Abandon waits for actual enabled editor semantics, not model state alone. No production dispatcher, original safety assertion or timeout was relaxed.

Build101's focused two CAPTCHA tests passed: replay ran on a DefaultDispatcher worker with Dispatchers.IO; acknowledgement and confirmed UI ran on Main; one replay and one callback were observed. This is a focused result, not full-suite acceptance.

## Current gate

Build102 passed the exact 171-test, 26-class ordered focused gate with zero failures, errors or skips. All 334 frozen source hashes matched; 145 fresh verification artifacts were archived. Method starts, ends and class order matched the expected inventory exactly. The real IO worker/Main callback/Main UI witnesses also passed in this ordered context. No source file changed during the run. Full ordinary remote unit tests, debug assembly and lint remain pending for the final publication tree. Focused counts are not added together or presented as the full suite. Publication remains a draft targeting the implementation branch; main remains out of scope until separate guarded acceptance.

## Explicit limits and retained risks

- These are synthetic intercepted API/OSS tests with no live upload, production account write, CAPTCHA submission or credential use. No live 403 resolution or real-service compatibility is established.
- Native composer hosting remains HostedScreen rather than Flutter's modal presentation and retained backdrop. Dialog captures are raw Dialog-window pixels, not a composited activity/dim-layer comparison. Physical device, real IME, TalkBack, performance and exact full-app parity remain unverified.
- Configuration recreation and synthetic lifecycle coverage do not establish durable process-death draft restoration. There is no automatic remote-orphan deletion.
- CI17's intermittent pending-reply readiness observation is not repaired or explained by this work. Later non-reproductions do not remove that risk; see `CI16_RETURN_DISPATCH_VALIDATION.md`. Any fresh ordinary-gate recurrence blocks acceptance and requires diagnosis, not repeated random reruns.
- Existing vault-v2 downgrade limits and local-versus-CI debug-signature incompatibility remain unchanged. No release signing, permission/security change, dependency upgrade or workflow change is included.
