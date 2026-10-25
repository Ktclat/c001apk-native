# Native Settings / Follow parity increment

## Source scope

Compared Flutter published `78e62d33` / tree `8ef26b5` (`apps/flutter/lib/pages/settings/settings_page.dart`, `pages/local_follow/local_follow_page.dart`) with shipped native `daea80fc` / tree `ab9c61abd2508d5943671835ce69e436f8b59238`. The isolated native baseline is `43753bd445a841b3449e6533295b3dcfc2828b49`, source-equivalent to that shipped tree. This is a functional increment, not a whole-app Flutter-parity claim.

## Actual changes

- **Follow content:** signed-in users can choose all/user/topic/product/app follows or the local list in Settings and the Follow toolbar. Server categories drive the existing typed `following` repository request; local selection keeps the previous server category. Guests use the local list. Choices are device-wide, persist across restart, and are not deleted by account changes. Switching categories does not follow or unfollow anyone.
- **Local profile follow:** an explicit button on `user:<uid>` adds a valid user ID to the existing local list, even if the server feed is empty/unavailable. Removing requires confirmation; cancellation leaves it intact. The guest label is 本地关注; the signed-in label is 悄悄关注. This component accepts only the local repository, not account credentials or a remote mutation client. This is an explicit-button adaptation, **not** Flutter's separate enable-switch plus long-press gesture.
- **Public image caches:** Settings shows the real Coil disk-entry size and clears the existing public loaders' memory/disk caches through Coil APIs after confirmation. Shared disk caches are counted/cleared once. Active readers can retain bytes; requests may repopulate caches. Partial failure and unknown size are distinct from successful empty state. There is no directory walk or deletion of account files, local records, drafts, private-message storage, or arbitrary cache directories. Uncached bounded article images are not falsely counted as persistent image cache.
- **Installed-app updates:** the existing `checkAppUpdates` preference now controls the manual update entry in Home → Apps. It does not initiate a query, widen package visibility, request a new permission, upload inventory, or install anything. The existing explicit application-selection/confirmation screen remains responsible for querying system-visible launcher apps; Settings retains a manual entry even when the list shortcut is hidden.
- **Native-release preference:** Settings and the existing native updater share the same preference. UI writes await the existing preference namespace's disk commit; a write failure displays the actual in-memory state and explicitly says persistence is unconfirmed. Toggling does not itself start a network check. The updater still targets only the native repository, not Flutter APKs.
- **Navigation and appearance:** returning from a child preserves the selected Settings category and list state. Popping Settings fully releases that saved state. Font-size control gains 0.05 steps and an explicit 100% reset while preserving the previously supported 80–160% range. A reading preview uses the same native reading styles/font choice as content. Whole-row labeled switches retain one actionable semantic target. Settings writes use explicit field-transform intents against current persisted state so unrelated edits and intentional selections equal to an old UI value are not lost.

## Full actual Flutter Settings coverage

| Flutter entry / behavior | Native before | This increment / remaining limits |
| --- | --- | --- |
| Dynamic system color; 19 fixed themes | Working | Unchanged actual theme consumers; Android 12+ dynamic behavior |
| Light/dark/system mode | Working | Explicit field intent avoids stale reselection loss |
| Floating Tab navigation | Working | Existing root visibility/motion behavior preserved |
| System / Material / Noto font choice | Working | Shared reading preview added; not per-choice miniature previews |
| Comfortable/original reading preset | Working | Uses real reading styles; preview added |
| Font size | Working, native 0.10 steps / 80–160% | 0.05 steps and reset; not Flutter's 80–130% confirmation dialog |
| Local-follow management | Working numeric-ID manager | Real local follow/remove button also reachable on user pages |
| Secret-follow enabled switch + long press | Not equivalent; flag selected viewed source | Explicit local button and source selector, **not gesture parity** |
| Follow server/local category selector (Follow page) | Persisted field/backend, no chooser | Six real choices in Settings and toolbar; source-dependent consumer |
| Display official emoji artwork | Missing; stored `showEmoji` unused | Still missing, no ineffective switch added; text markers retained; original artwork distribution needs separate rights review |
| User/topic blacklist management | Working combined screen | Unchanged; Flutter clear/export is selected-type scoped, native combined scope is disclosed |
| Contextual block from feed/user menus | Missing/partial | Outside this slice; settings management is not contextual-action parity |
| Record history | Working | Clear explanation: disable stops new records, does not delete old history |
| External browser preference | Working | Clear explanation; internal app links remain internal |
| Automatic own-app release checks | Working but buried | Direct Settings control plus existing manual updater; durable-write failure surfaced |
| Installed-app update shortcut switch | Stored but unused | Actual list-entry consumer; no new package permissions or automatic query |
| Clear image cache | Placeholder | Real current Coil cache APIs, confirmation, size and error/result handling |
| Official ShuzlmID import | Missing | Separate reviewed session-safe increment required; not covered by this slice |
| Manual device information | Missing | Remains explicitly unavailable |
| Protocol parameter editor | Missing | Remains explicitly unavailable |
| Account management | Working | Existing add/switch/remove routes retained; preferences intentionally device-wide |
| About / licenses | Working native-specific page | Not a complete dependency or original-artwork license audit |

## Verification and limits

Initial focused gate compiled production and all test sources; 55/58 executed cases passed. The three failures were one JUnit class initialization error (a test inferred a non-Unit expression return) and two UI fixture lookups, so this was **not** a passing gate. Its exact manifest, XML and fresh synthetic PNGs are preserved under `native-settings-focused-v1`. The subsequent revision strengthens equal-value intents, exact child-return scroll assertions and short-window coverage; it must be verified on its own source snapshot.

Verification uses Robolectric's native graphics and offline/synthetic account/repository fixtures. No real account, service acceptance, package install, device identifier import, physical device, TalkBack, or device performance was exercised. The inherited native debug build/signing limitations remain. Final full native suite, lint/build and independent review belong to the combined integration gate; neither this document nor focused test counts substitute for that gate.

### Focused v3 acceptance

Exact runtime/test source `bf0f213ce54ccb328475831c7b8ba063676bf9dc`, tree `2ce47d151e78e56a94401d35b1162fdb918a9f39`, passed the offline focused gate in 1m55s: **67 tests / 16 classes, zero failures/errors/skips**. All **428 frozen source hashes matched**. The gate includes all eight real Coil cache tests, field-intent/restart/failure tests, actual root source selection and user-local-follow actions, exact nonzero category-scroll/anchor retention across child Back, saved-state restoration, and dark 2× system-font/short-landscape reachability. Eleven native-graphics Settings-root PNGs were generated. Independent scoped review accepted that exact source and evidence; it did not claim physical-device or full-app parity.

Evidence is preserved in `native-settings-focused-v3` outside the source checkout. No APK assembly, lint or whole-suite result is asserted by this focused pass. The later integration gate must validate the merged final source. This paragraph is a documentation-only addition after the accepted runtime/test freeze.
