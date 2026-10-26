# Native public-image repair — 2026-10-26

## Established defect

The previous native parser retained only strings beginning with `https://`. Both published Coolapk API examples (avatar, logo and feed picture fields) and a fresh, anonymous official `/feed/74194711` HTML response contain `http://` CDN URLs. Native therefore discarded valid media before Coil could request it. Flutter's actual `networkImage` accepts both HTTP and HTTPS; its global HttpOverrides sets its app User-Agent.

The retained native APK contains Coil 2.7's `HttpUriFetcher`/factory. This is **not** the Coil 3 missing `coil-network-okhttp` dependency bug. The public avatar succeeds using both okhttp and Dalvik User-Agents, so there is no evidence for a UA/referrer workaround.

## Frozen repair

- Commit: `707e6c9252e5ec376d442410d09cc25b2560cd6f`
- Tree: `6abc9395e996b6150570c780bb24724cfb7182d5`
- Base: `46237435b20422aab504358f4f1ccb1e874463c2`
- Upgrade HTTP/default port 80 and protocol-relative URLs **only** for `image.coolapk.com`, `avatar.coolapk.com`, and `static.coolapk.com`, retaining path and query. Valid HTTPS remains HTTPS; malformed/credentialed/fragment/unsupported-port URLs are rejected.
- Apply that normalization to feed/reply avatars and pictures, home/card/app logos, profile avatars, event pictures and bounded article media. Article origin, DNS/public-address, proxy, format and size checks remain in effect.
- Register one production public-image loader through `NativeApplication`/`ImageLoaderFactory`. It retains Coil's real HTTP fetcher and bitmap/GIF decoders, uses no cookies or authenticators, and disallows scheme-changing redirects. No account API transport/header/identity is shared with it.
- Shared public-image UI shows a real error icon and failure semantics instead of a blank image on transport/decode failure. Existing gallery loading and explicit retry are unchanged. Private-message image resolution and cache isolation are intentionally unchanged.
- Cleartext Android policy remains disabled. No alternative CDN, security-policy workaround, TLS weakening, cache purge, synthetic production content or publication was added.

## Before/after checks completed

**Before:** unchanged production source at the base, plus the new regression test only: 3 tests / 3 assertion failures / 0 errors or skips. Actual empty logo list, null avatar, and acceptance of credentialed HTTPS reproduced the defects.

**After:** 61 focused tests / 0 failures / 0 errors / 0 skips in 1m34s. Covers the same three parser tests, URL safety, profile/event/article cases, singleton registration, real Coil HTTP fetcher+PNG decoder with a controlled anonymous transport, terminal HTTP567 failure/no repeated request, URL-change recovery, and existing feed/reply/icon-grid/home/entity UI tests. The controlled transport tests are explicitly **not** live-CDN acceptance and do not inject a Coil interceptor or fake bitmap.

Local receipts are archived in the parent workspace under `native-image-evidence/before/`, `native-image-evidence/after/`, and `native-image-evidence/reviewer/`; they are evidence artifacts, not bundled app assets. The regression classes are committed and rerunnable. The screenshot harness and raw external response bytes remain test-only evidence, never production resources.

## Independent live evidence and limits

- Anonymous `https://avatar.coolapk.com/data/000/53/63/81_avatar_middle.jpg?1456135138` returned HTTP200 `image/jpeg`, 5,221 bytes, decoding to a real 120×120 photograph of daisies. HTTP and HTTPS response bytes match. SHA-256: `26a7595a46b636b79ffd11bd973713980524bb643ca5be50113e1964b6cfddf7`.
- Image CDN requests returned HTTP567 with explicit EdgeOne RestrictedAccess HTML. Requests to that blocked host stopped; no alternate origin/proxy/header bypass was attempted. API app/search anonymous probes also returned HTML567, not current usable API payloads.
- An unchanged independent NativeApp/ApiDecoder/production-Coil screenshot harness fetched the allowed avatar over HTTPS and decoded actual JPEG pixels. Baseline: HTTP inputs were discarded, HTTPS controls displayed real daisies, entity crop contained 0 blue-image pixels. Repaired source: the final settled frame showed the HTTP-derived feed avatar, both icon tiles and entity image as the actual photo; entity crop contained 6,242 blue-image pixels out of 11,025. The independent AFTER run passed 1/1. Restricted image-host HTTP567 responses were replayed locally, not requested again; their error icon/text stayed visible. Layout/data are test scaffolding and the reused JPEG is not the app’s real logo. Initial AFTER frame 01 was captured before avatars finished loading and is not the settled headline; frame 04 is the reviewed final state.
- BEFORE/AFTER harness SHA-256: `231d3c2940dab42c04e3b25fd575c53ee21173c236840632608876b3b3ac9c08`. Immutable local evidence is under `native-image-evidence/reviewer/before/` and `native-image-evidence/reviewer/after/` in the parent workspace. Both frames were visually reviewed at original detail. Separate API26 software-emulator baseline launch/screens are not the same evidence as this Robolectric production-component render.
- This is not proof that every live CDN or the user's physical device is fixed. Current app API data, all imagehost access, physical device behavior and hardware performance need their own evidence. A failed public icon/avatar remains failed until its component is recreated or URL changes; gallery has explicit retry.

## Integration boundary

This document records the independently tested image delta, not a completed combined release. The settled-navigation delta is documented separately in `docs/NATIVE_NAVIGATION_THEME_INCREMENT.md`. Combined source must still pass the full font/provenance/build/test/lint gate and have its exact APK checked before delivery. No remote push, release or physical-device acceptance is implied by either focused result.
