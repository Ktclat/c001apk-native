# Native implementation status

This supplements the source inventory in FEATURE_PARITY.md. **NOT ACCEPTED; no percentage of parity is claimed.** “Implemented” means code exists, not that live-service/device acceptance passed.

| Area | Current repository implementation | Still open |
| --- | --- | --- |
| Foundation | Kotlin/Compose, minSdk 26, app/domain/data boundaries, pinned build, separate native package | Final-source build/test/lint gates and independent device acceptance |
| Navigation | Three root tabs, six Home sections, stacked floating labels, stable click boxes, switch-only decorative jelly, reduced-motion/TalkBack observers, bounded route/cache state | Full-screen portrait/landscape/IME/large-font evidence; hidden dock currently reserves its bottom slot; measured frame times |
| Public reads | Feed/detail/replies/search/topic/product/user/app source-derived routes, page cursors/errors, bounded decoding off main thread | Live access (one probe stopped on network-path HTTP 567), advanced filter UI, richer card/article layouts, pull-to-refresh/automatic paging parity |
| Local data | Favorites/history, local follows and chronological pager, blacklist filtering/import/export, corruption failure states | Instrumented SAF/storage/restart testing and richer media presentation |
| Appearance | Theme mode/dynamic color, font scale, reading preset, floating navigation option | Three font assets, full fixed-color palette and remaining preference consumers are staged separately |
| Accounts | Session generation, encrypted vault, scoped cookies/proofs, explicit recovery, account manager and manual login state/UI | Provider login validation, isolated official webpage branch, migration, device Keystore interruption/recovery acceptance |
| Social | Pinned text post/reply/like/follow/chat/notice contracts, composer/notice/chat screens, temporary image-upload boundaries | Complete detail action wiring, full composer/editor/media parity, authenticated read/write acceptance with separately authorized accounts |
| Platform/media | Launcher-visible installed-app inventory, scoped temporary image selection | Full inventory opt-in variant, native update checks, gallery/save/share/GIF and public-link handling are staged separately |
| Verification | Production compile/debug packaging observed; 98-test run: 97 passed, 1 cancellation-fixture teardown timeout; actual portrait/landscape dock renders inspected | All-tests pass, full-screen rendered PNG inspection, final lint, full-screen tests, CI and real-device/performance evidence |

No fake feed fallback is used by production repositories. Synthetic test fixtures are isolated to tests and must remain labeled as such. No real-account writes, user-device installation, permission grants, release signing, or automatic merges are part of this verification.
