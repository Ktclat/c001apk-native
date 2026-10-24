# Migration and acceptance gates

Reference Flutter main: `52202fdf9e5847f13cabfa361c47cd0de7404dad` (verify against parity inventory). Desired navigation refinements also use unmerged PR #1, head `3f04795ca6fe41284f88ea826c8ab327d4fbe28b`; the migration does not merge that PR.

1. **Native foundation (in progress):** reproducible debug build, minimum Android 26, Kotlin/Compose state-driven UI, three root tabs, six Home tabs, stacked icon/labels, decorative-only jelly indicator, preserved screen state, safe-area and accessibility checks.
2. **Read-only browsing:** real source-derived feed/detail/reply/search/topic/product/user/application contracts, paging/refresh/error/retry, images and links, local launcher-app inventory. Synthetic parser tests are separate from actual API verification.
3. **Local preferences and tools:** reading/appearance settings, blacklists with JSON import/export, browsing history, cache management, update checks, package/media actions with deliberate platform permission handling.
4. **Accounts and authenticated parity:** isolated account sessions and cancellation generations, secure credential storage, login/logout/switching and WebView boundaries. Real accounts or credentials require a separate user-confirmed test flow.
5. **Write actions and media parity:** comments/replies/posts/likes/follows/messages/collections as actually inventoried. No real user-account write occurs during automated development verification.
6. **Acceptance:** feature-by-feature matrix, Android instrumentation, independent UI/accessibility review, portrait/landscape and large-font screenshots, gesture navigation, interrupted/repeated flows, measured frame timing and state/refetch counters on representative hardware. Until these gates pass, do not declare parity or merge/release readiness.

## Safety and quality constraints

- Never substitute sample content for unavailable production data.
- A challenge or login requirement is an explicit state, not a retry loop.
- No global mutable cookie; account changes must invalidate jobs and partition caches.
- Data model parsing tolerates unknown server fields; errors avoid sensitive content.
- Current baseline queries launcher-visible apps only. A separate direct-distribution full-inventory variant is planned with explicit local scan consent/revocation and no inventory upload; it is not implicitly Google Play eligible. No actual device permission is granted during development.
- No account identifier harvesting or silent credential imports.
- Preserve AGPL license and attribution for source-derived contracts.
