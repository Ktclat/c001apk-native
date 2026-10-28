# Source and third-party notices

The application is AGPL-3.0, matching the pre-existing repository license. Source-derived API contracts and native-compatible protocol formatting reference Ktclat/c001apk (Flutter), based on TG-Twilight/c001apk. The Token V3 algorithm also derives from coolapk-desktop code under MIT; its complete notice is preserved in docs/licenses/coolapk-desktop-MIT.txt and provenance is recorded in ClientProtocol.kt.

AndroidX/Compose, Kotlin, kotlinx.coroutines, kotlinx.serialization, OkHttp/Okio and Coil are used under their respective upstream licenses. jBCrypt 0.4 is used under its ISC-style license. These dependencies are obtained through official Google Maven and Maven Central repositories, not vendored source. A complete dependency/license audit remains a release acceptance gate.

## Bundled Noto Sans CJK SC

Unmodified Regular, Medium and Bold font assets follow the pinned source assets documented in `app/src/main/assets/licenses/NotoSansCJK-NOTICE.txt`. Copyright © 2014, 2015 Adobe Systems Incorporated. Distributed under SIL Open Font License 1.1; full license is bundled alongside the notice.

## Flutter icon and launcher reuse

Native navigation and feed-action icons use mechanically converted original contours from the MaterialIcons-Regular.otf shipped with Flutter 3.24.5, rather than hand-drawn substitutes. Material Icons copyright: 2019 Google LLC. The exact SDK font's supplied CC-BY-4.0 license and attribution/change notice are preserved in `app/src/main/assets/licenses/FlutterMaterialIcons-CC-BY-4.0.txt` and `FlutterMaterialIcons-NOTICE.txt`. Flutter's IconData mapping copyright and BSD license are preserved in `Flutter-BSD.txt`. The extracted artwork retains its original license; the native application source license does not replace third-party rights.

Launcher foreground, monochrome reference and dynamic color resources are reused unchanged from the existing Ktclat/c001apk Flutter Android project, inherited from TG-Twilight/c001apk and c001apk-flutter. Its original AGPL source attribution remains in effect. The inherited Android 12 Monet color attribution to bggRGjQaUbCoE/PiliPlus is preserved in `FlutterLauncher-NOTICE.txt`, with the original GPL-3.0 license in `PiliPlus-GPL-3.0.txt`. No PiliPlus graphical artwork is included. The source record does not supply a separate original-artwork grant for the existing c001apk infinity mark, and this reuse does not assert ownership or create new artwork/trademark permissions.

## Text emoticon tokens

The text-only Composer picker derives exact token names, order and three groups from Ktclat/c001apk AGPL-3.0 source, itself based on TG-Twilight/c001apk. Provenance and adaptation are recorded in `app/src/main/assets/licenses/TextEmoticons-NOTICE.txt`. That original increment was text-only. The later inline renderer copies 183 unchanged PNGs and the exact catalog from Flutter commit `0d9ed002980d935c1c9351393e9f16784aa78903`; see `app/src/main/assets/licenses/InlineEmoticons-NOTICE.txt` and `app/tools/emoticons.sha256`. Artwork remains the property of Coolapk and its respective rights holders; the source record supplies no separate artwork redistribution grant, and the project AGPL license does not relicense it.

## Native detail Markdown

CommonMark core and GFM strikethrough are pinned to `org.commonmark` version **0.24.0** from Maven Central. Copyright (c) 2015 Robin Stocker; BSD 2-Clause. The complete upstream license is bundled as `app/src/main/assets/licenses/CommonMark-BSD-2-Clause.txt` and exposed in the offline notices page. No HTML renderer, WebView, remote image renderer, autolink/email extension, or global document cache is used.

Dependency/API reference: [commonmark-java 0.24.0](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.24.0). Detection and source-view behavior are adapted from [TG-Twilight/c001apk Markdown increment 98526cc](https://github.com/TG-Twilight/c001apk/commit/98526cc30cc6ed5d96fc4bed32ee03dfc92b7024), under the existing AGPL-3.0 source lineage. This is a deliberately smaller native subset, not a full Dart Markdown implementation.
