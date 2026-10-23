# UNACCEPTED structured-article candidate: CI validation only

This candidate is based on the reviewed native baseline `a616a61ec38e5c2879cc42ce894400749c9f9f80`. It is intended for a separate draft validation pull request, not an update to the accepted baseline branch or main. It is **not yet fully tested, accepted, or suitable for release delivery**.

## Bounded change

Adds source-ordered structured article cover/title and text, image and share-URL blocks. Unsupported or malformed blocks produce an explicit whole-article fallback instead of silently disappearing. Arbitrary HTML inline images, audio/video, composing and transfers are not included. Existing ordinary-message reading and memory-only reader bookmarks remain supported; article identity includes the complete typed source and decorations. Resource limits, cancellation, captured-session ownership and generation checks remain required.

The article-only image loader uses bounded static-image decoding, HTTPS/public-host and DNS checks, no cookies or authentication, no redirects, and cancellation cleanup. It does not silently bypass a configured system proxy: unsupported proxy routing is reported visibly with an explicit fallback. It does not change OS VPN/network settings. Successful images have no permanent extra action row. Very thin image targets intentionally retain a fixed minimum 48 dp accessible region. Share-card targets also retain a 48 dp minimum.

## Evidence and unresolved validation

- Build 76 ran 196 focused tests: 194 passed, two failed. Subsequent evidence isolated a test synchronization issue and an Activity-versus-Dialog Back dispatch error. The corrected tests retain visible state, request/count, geometry and no-refetch assertions.
- Build 78 ran 27 focused tests: 25 passed. The retry and actual focused-dialog Back cases passed. Its only two failures were a test expectation of 0.25 sp letter spacing, while the pinned native Material3 bodyMedium token is 0.2 sp. Production caption size/line height assertions of 14/20 sp passed.
- The current candidate changes that one test expectation to the actual selected native typography token. It does not alter production behavior for this token difference. Authentic Flutter bodyMedium remains 0.25 sp; this small platform-token difference is documented rather than represented as exact parity.
- Build 79 compiled production and tests, then lost its execution session after the first test started. No terminal result, fresh result XML or completed test is available. All 314 frozen source/resource hashes remained unchanged. Builds 73 and 77 also lost execution sessions. No OOM, application crash or test failure cause is inferred from those interruptions.
- There has been **no successful coherent full test, assembly and lint gate for this article candidate**. The expected complete suite is approximately 640 tests; actual CI XML is authoritative. Previous focused results are not a substitute for the complete gate.
- Authentic synthetic Flutter comparisons support article order, normal loaded-image geometry and reachable compact-layout tails. The accidental inherited 24 sp caption line height was corrected to 20 sp. Fresh matching captures for the final test correction still need review. Native and Flutter comparison fixtures use the same purple seed (seed index 0), but different synthetic image pixels and platform typography tokens, including the 0.2/0.25 sp letter-spacing distinction above. These are not pixel-parity, real-device or real-service results.

CI must execute the existing workflow's complete build, synthetic unit tests and lint, and retain result XML, screenshots, reports and the debug APK for independent inspection. A green CI run alone does not authorize merging or delivering this candidate without source, artifact and visual review. Live HTTP 403 compatibility, actual-device/TalkBack behavior and complete Flutter feature parity remain unverified. Existing vault-v2 downgrade incompatibility and debug-signature limitations remain unchanged.

## Validation workflow

The existing workflow runs on pull requests, manual dispatch, and pushes to main. A non-main validation branch push alone does not trigger the push event. Create a separately labelled **draft** pull request targeting `implementation/native-foundation` at the pinned baseline for this exact candidate; keep the existing implementation pull request and main unchanged. No workflow permission, action pin, credential, signing-key or security-setting change is included. Artifacts are explicitly unaccepted validation outputs, not a release.
