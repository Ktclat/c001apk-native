# Reachable user-space header increment

Subsequent bounded work links the following/fans counts to real user lists and adds public information/last-active presentation. See [user connections parity](USER_CONNECTIONS_PARITY.md); the historical scope and evidence below describe the earlier header-only increment.

## Source contract

Reference Flutter commit: `0d9ed002980d935c1c9351393e9f16784aa78903`.

The existing avatar/contact/local-follow links already navigate to `user:<uid>`, but previously that route only loaded a feed and a UID-only local-follow control. Flutter `pages/user/user_controller.dart` reads `GET /v6/user/space?uid=…` independently of `GET /v6/user/feedList`; `components/cards/user_info_card.dart` supplies the identity header. `logic/model/feed/datum.g.dart` maps received likes from **`be_like_num`**, not the Dart property name.

The native route now mounts the actual user-space read and header. The unused `userProfile` browse method calls `/v6/user/space`; the separate own-account `ownProfile` method still calls `/v6/user/profile` with its existing account-bound contract.

## Included behavior

- Verified requested/root/nested UID consistency. Supported route IDs are bounded, positive numeric IDs; malformed IDs do not dispatch reads. No author name, avatar, counts or follow status is inferred from a feed or account state.
- Bounded nested-name/root-name fallback, avatar, cover and nullable level/feed/received-like/following/fan values. Missing or malformed counts remain unknown, not fabricated zeros. Count labels are informational, not inert navigation buttons.
- Existing anonymous `PublicImage` media loader and known-CDN HTTPS normalization; no account cookies on image requests, no new cleartext policy.
- Independently cached profile and feed states, retries, and route-owned cancellation. Whole-page explicit refresh reloads both admitted reads. Empty/failed feeds keep the profile. Profile failure does not suppress a permitted feed. A local blacklist suppresses the user's entire feed, including requests and pagination, while retaining the identity header.
- Existing local/secret-follow action remains explicitly local. If the verified profile is available, its name/avatar are saved with the UID (name safely bounded to 200 UTF-16 units; URLs above the local 2,048-unit bound are omitted, not truncated); no account-follow mutation is introduced.
- Child-detail Back reuses the retained profile/feed and list bookmark. Non-cooperative abandoned reads cannot overwrite a new visit. This is memory/composition continuity, not durable process restoration.

## Deliberate limits

Normal account follow/unfollow, last-active (`logintime`) presentation, user information dialogs (including gender/registration fields), followers/following destinations, DM shortcuts, cover/avatar gallery gestures, scoped user search, a new user-menu block action, name/UID copying and profile link copy/share/report are not included. Alias/username `/u/…` paths still need a separate resolution contract; they show an explicit invalid-ID state instead of issuing a guessed request. Object-profile CAPTCHA continues to show an explicit safe failure/retry; existing feed/list verification ownership and authentication policy are unchanged.

## Verification provenance

Tests use real production `NativeApp`, HTTP repository, decoder, header and feed components. API responses and image artwork are synthetic and are always terminated locally; they are not live-service, real-account or physical-device acceptance. Baseline capture uses source `99b722d` with only the capture fixture added; candidate capture uses the profile increment. The exact local result record follows. No live social writes were attempted.


### Local focused gates — 2026-10-26

- **Before:** `99b722d0ba2d3bd6f6f1ef5b3592a223dde2a486` plus one test-only fixture. Actual Home avatar → user route passed **1/1** and visibly showed only the UID local-follow control/feed, not a user profile. PNG SHA-256: `890c552b0b7254acff5aece1627d69d28f4215330a5e465f4bc5e3ce85043b16`.
- **First candidate:** `1d3a886` compiled; **138 tests, 137 passed and one failed**. The newly adjusted `entityDark` fixture tried scrolling to asynchronously prepared body text too early. Its failure frame showed preparation in progress; the later semantics dump contained the expected text. The test now scrolls to the stable feed node and still requires the exact rendered body. This first run remains a failure, not a pass.
- **Independent source review:** identified a real local-follow regression when valid public-profile metadata exceeded the local schema's smaller bounds. The save boundary now safely bounds the name and omits an oversized optional avatar URL; an actual-root long-profile test verifies saving succeeds without network mutation or splitting a surrogate pair.
- **Corrected candidate:** `f52359f21e23223c4ceef3df6969570dd77602ff` compiled and passed **139/139 tests across 18 nonempty classes**, zero failures/errors/skips. This includes **11 actual NativeApp profile cases**, decoder/repository/lifecycle tests, existing contact/card routes, the article-to-user route, own-account profile scope, and related read-verification/social/local repository regression tests. Overlapping earlier runs are not added to this total.
- **Frames:** ten original 1031×2236 PNGs, Robolectric API35 `w393dp-h852dp-420dpi`, actual production UI and app fonts. They cover normal author navigation, child Back, independent profile/feed errors, empty feed, missing metadata, blocked feed, dark 1.6× font, 2× local controls, and the long-metadata local-follow result. The profile-error capture explicitly waits for the independently rendered feed body. Core normal/dark/large-font/error/empty frames were inspected at original detail; these are synthetic fixtures, not device screenshots or live-profile acceptance.
- Normal AFTER PNG SHA-256: `d9fbf94538b8bd9c4c2ccbd4691cf06a52eec9aaf7546cf08c0be7ec36a80cd8`; restored-child PNG is byte-identical. Profile-error PNG SHA-256: `907ee094c3a51baef1d369c52b173a8e2ee8f5f2a211c96b1de38b1b660aa371`.

The focused gates **did not run final assembly, lint, the full suite, CI or physical-device/TalkBack verification**. Those remain integration gates; these focused results do not authorize claiming complete Flutter parity or a live-service fix. Exact logs/XML/frames and hashes are preserved in the task workspace under `native-toolchain/profile-evidence/{before,focused-1,focused-2}`.

## Child-return preview geometry regression guard

The dedicated user route must continue providing the existing `BrowseViewModel.previewGeometry`
store to its feed list, just as the previous generic route did. A late combined-candidate test
caught a missing provider: after opening a child detail and returning while the long synthetic
preview parser was held, the prepared 984 px preview collapsed to 48 px. The correction only
restores that existing owner-scoped provider; it does not change cache bounds, persistence,
account isolation, or parsing policy.

`childBackRetainsExistingUserFeedPreviewHeightWhileParserIsPending` exercises the actual
`NativeApp` route and asserts unchanged pending height, reuse of the model-owned store, restored
body position without corrective scrolling, rendered content after release, and no duplicate
profile/feed requests. Its pending frame is an intentionally held **synthetic** parser, not a live
service result. The first reproduction attempt had an unmerged-semantics finder error; the
corrected before test reached and failed the 984-to-48 px production assertion. Both receipts
remain external under `native-toolchain/profile-retention/`; final combined validation is recorded
separately so the earlier `9ddd52e` full gate is not misrepresented as covering this correction.
