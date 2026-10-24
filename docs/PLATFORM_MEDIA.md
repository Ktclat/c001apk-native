# Platform / media implementation evidence

Source baseline: Flutter `52202fdf9e5847f13cabfa361c47cd0de7404dad`, imageview_page.dart, MainActivity.kt, utils.onOpenLink and logic/app_update.

## Implemented source, awaiting integrated build/device verification

- MEDIA01: Compose source-sized preview (all images remain reachable): dimension-aware single-image widths and bounded long-image height, 2×2 four-image layout, 5dp gaps, multi-image cover crop, 12dp border and GIF/long-image badges, GIF-capable shared Coil loader; full-screen swipe/pinch/pan with accessible zoom/reset controls, page count; save current image with system CreateDocument and save all to a user-chosen SAF tree. Clipboard writes occur only on explicit Copy. Sharing downloads actual image bytes into a scoped cache FileProvider and grants only read URI access through the Android chooser.
- Anonymous image download uses a separate client, no API cookies/headers, no HTTPS-to-HTTP redirect downgrade, recognized image MIME types and streaming 30 MiB cap. Failed partial cache files and partial save-all destination entries are removed. Save-all reports how many files completed before failure; it does not claim all succeeded.
- APP01 partial: local launcher-visible, non-system packages only, name/version/package/version-code/update-time and lazy icon loads, deterministic name/update sorting and refresh. Flutter enumerates all non-system packages, including those without launcher activities. Android package-visibility restrictions mean these scopes are not equivalent. No QUERY_ALL_PACKAGES requested. Full scope would need deliberate permission/privacy/distribution policy review. Inventory is not uploaded by these components.
- UPDATE01: native repository only (`Ktclat/c001apk-native`), strict stable semantic version comparison, exact release and asset paths, no foreign Flutter APK assets, no private account headers, distinct no-release state, manual check, process-scoped automatic guard, persistent skip and enable preference. Auto prompt requires a single root-hosted NativeUpdatePrompt using app-scoped NativeUpdateService. Downloads are explicit browser actions; no install permission or automatic download/install.
- LINK01 partial: exact official hosts, relative paths and coolmarket links for feed/event/user/app/game/topic/product/dyh/coolpic/page. Unsupported routes remain explicit; unknown official HTTP(S) goes to external browser handling rather than guessing a native destination. `/user/...` following list is not the same as `/u/...` profile and is intentionally not misrouted.

## Remaining device acceptance

Validate Android 26/28 and modern scoped storage pickers, cancellation/rotation/process death during document save, grant lifetime and chooser behavior, actual GIF decoding, pinch-vs-pager gesture arbitration, TalkBack actions, high font scales, failed downloads, cold/warm native release checks, package list with uninstalled apps, offline/retry and permissions. Automated unit tests are not device acceptance. No actual account write, upload, release, installation or user-device permission action was performed.

## Public web destination

- Unexported `PublicWebActivity` must run in `:public_web`, with runtime process-name assertion and data-directory suffix configured before WebView creation. Public cookies/files never use the main process/account profile directory. API26/27 and unavailable isolation fail to the external browser instead of sharing a default cookie jar.
- HTTPS-only in-app navigation/subresources, explicit visible URL/origin and external-open action; Standard JavaScript and origin-scoped DOM storage supported inside the isolated renderer for modern-page functionality; no native bridges. File/content access, mixed content, automatic downloads/popups, JS modal dialogs, file uploads and permission grants disabled. Media requires user gesture. SSL errors canceled; Safe Browsing returns to safety. First-party public browsing cookies remain confined to this public directory and persist across public browser launches until app data is cleared; closing a page does not clear them. They are never imported into AccountStore or interpreted as app login. Third-party cookies disabled. No tool performed real sign-ins.
- `openWebLink(context, url, preferExternal)` is the settings consumer; do not expose external-browser setting until root link dispatch calls this function. Web page back/close/retry implemented; device WebView isolation and lifecycle acceptance remain unrun.

## Installed-app update checks (APP03 partial)

- Real update-check repository and selection/results screen: launcher-visible inventory initially selected-none; explicit review dialog lists exact package names/version codes and `api.coolapk.com` before POST. Body is Base64 JSON `{packageName: "0,versionCode,0"}` in multipart `pkgs`, query `coolmarket_beta=0`. No background checks, label/icon/timestamp transmission or automatic installs. Tests use synthetic `org.example` inventory only.
- Returned package must belong to the submitted selection; malformed data fails visibly. Newer version results include name/version/size/changelog and app-detail navigation.
- Strict in-app DownloadManager delivery remains blocked: Flutter fixtures/source accept arbitrary 302 Location and do not identify exact allowed CDN hosts. No live endpoint probes were performed and no host wildcard/guessed allowlist is substituted. The real source endpoint resolves 302 without following; validated public HTTPS targets are shown with their exact origin and a separate explicit system-browser confirmation, with no Coolapk request headers/cookies transferred. Local/private literals, URL credentials, non-443 ports and unsafe schemes are rejected. The website may start download only after the user selects this handoff; no automatic installation occurs. An official app webpage is also available. This browser handoff is not claimed as verified in-app DownloadManager parity.

## Offline root-render test setup

Before composing NativeApp/NativeUpdatePrompt in an offline fixture, set the app-scoped `NativeUpdateService.enabled = false`, or synchronously commit `enabled=false` to SharedPreferences `native-release-updates`. Automatic `check` returns Suppressed before creating a network request. Do not render the default-enabled startup updater in synthetic screenshot tests. Media fixtures must use empty/local image inputs rather than live remote URLs.

## Visual media fixtures

MediaGridLayoutTest verifies source aspect thresholds, four/nine-image columns and badge parsing. MediaGridRenderingTest injects synthetic BitmapDrawable content through Coil (no network) and exports landscape-single, long-single, four-image and nine-image PNG fixtures. LocalMediaImageLoader supports the same offline injection in full-app renders. These fixtures are component evidence, not actual-device acceptance.
