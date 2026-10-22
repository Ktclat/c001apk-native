# Local build and floating-navigation diagnostics

This is an opt-in, local report, reachable through **我的 → 设置 → 关于**. Opening this panel does not submit the report. Only the visible copy button puts it on the system clipboard for the user to share themselves. It contains no account names, cookies, credentials, device identifiers, installed-app inventory, filesystem paths, or automatic telemetry.

## Default scroll behavior

Floating navigation hides after consumed downward scrolling by default and reveals on reverse scrolling. There is no separate scroll-hide mode or picker. Touch exploration does not disable this default. An accessibility action labeled **显示底部导航** is exposed on existing root scroll containers and labeled toolbar controls while the dock is hidden; it reveals the dock and resets consumed-scroll/fling history without changing system settings or adding an invisible dock target.

The keyboard and inner-page gates remain in effect. Active root search restores the dock while the keyboard is absent. The existing 28/20 dp consumed-scroll thresholds, user-fling tracking, fixed hit targets, and stable trailing content clearance are unchanged. The real animator-duration reduced-motion signal suppresses decorative animation only; it is not a scroll-hide blocker. Actual TalkBack action discovery, traversal and gesture routing still need physical-device validation.

The native signal is `AccessibilityManager.isTouchExplorationEnabled`, not Flutter's broader `accessibleNavigation` flag. A Flutter report is not proof that the same condition occurred in the native app.

## Report contents and limits

The report identifies the package version/build type, available Git revision and branch, dirty/unknown state, and a deterministic source-scope fingerprint. It also shows the measured content size, density, system/effective font scale, separately named inset values, native touch exploration, reduced-motion state, floating-navigation preference and default scroll behavior (with no user-selectable mode).

The fingerprint covers an explicit production-source/build-input whitelist, not the whole APK. It excludes secrets, local paths, generated timestamps, binary fonts, build outputs, tests and documentation. It is not an APK signature, provenance attestation or reproducible-build proof. Preparation assumes a trusted checkout without concurrent source mutation; the helper is not a security sandbox. Unknown or unavailable Git information stays explicit. A dirty build must not be treated as the exact published commit.

Reported window measurements describe the current native window. A synthetic viewport derived from a Flutter report remains a test mapping, not a physical-device reproduction or a measurement of HyperOS behavior. Insets with different meanings must not simply be added together.

## Verification

Build-provenance self-tests run explicitly in CI using the installed JDK, without downloading another test framework. Android unit/Compose tests cover settings persistence, visibility policy, report contents and manual copying. Actual root-page policy/listener/pointer/inset cases belong to the separately recorded Android test gate. Consult the verification report for the exact tested revision and results; this document does not imply device, accessibility-service gesture-routing, performance, or complete Flutter-parity acceptance.
