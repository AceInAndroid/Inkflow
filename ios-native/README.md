# Inkflow native iOS / iPadOS foundation

This is a separate SwiftUI app, not the Flutter `ios/` runner. Requires Xcode 16+
(with the iOS platform and a simulator runtime installed), iOS/iPadOS 17+, and an
Apple Silicon Mac for the supplied simulator framework target.

Verified on 2026-11-28: the real SharedCore scheme built and ran **41 XCTest
methods on each of iPhone 17 and iPad (A16), iOS 26.4**, with zero failures or
skips. Foundation host checks passed 25 tests. The capabilities below describe
implemented behavior; full SwiftUI restoration, interactive reader/speech flows,
the four-language/four-style visual matrix and physical devices remain outside
this acceptance. Exact evidence and remaining M7 scope are in `VALIDATION.md`.

Supported now:

- Actual Home / Library / Me navigation: bottom tabs on compact widths and a
  three-entry sidebar on regular iPad widths. Both Home and Library open the
  existing reader full screen, with a close action back to the source screen.
- Classic, wallpaper, minimal, and static high-contrast E-INK Home styles share
  one projection of `LibraryStore` metadata. Continue/recent books, stored progress,
  and opened/completed counts use the real index; there are no invented reading
  durations. Old indexes without `lastReadAt` retain their existing anchors.
- Device-local style, component visibility, custom motto, wallpaper, system/light/
  dark theme, and automatic/manual interface-language preferences. Me exposes
  settings and an accurate capability summary. Wallpaper images are copied from
  Files (20 MB limit), normalized to JPEG at up to 2048 pixels, and use a solid
  background if missing. The minimal style intentionally shows only the clock,
  motto and featured book; other entry points stay in the navigation/settings.
- English, Simplified Chinese, Japanese, and Korean interface copy. Automatic
  selection uses **supported region → explicit IANA time-zone mapping → first
  supported raw `Locale.preferredLanguages` entry → English**. A manual choice
  overrides every system signal. Region/time-zone changes are reevaluated on
  activation and system notifications; switching language does not change the
  reader identity, speech voice, book titles/text, custom mottos or questions.

- Import UTF-8 or BOM-marked UTF-16 `.txt`, and unlocked `.pdf` files from Files
  (up to 64 MB each). Files are copied into the app sandbox before access ends.
- A persistent local bookshelf with deletion and reading-position restoration.
  Multi-file import reports success/failure per file and continues after failures.
  Results stay visibly accessible in Library; importing never forces a reader
  presentation or invents a reading event. Each successful row opens the actual
  stored book explicitly.
- SHA-256 duplicate detection on normalized content (including legacy entries
  without a digest). Reimporting the same book preserves its identity, title,
  anchors and font size. If a digest-bearing entry's managed copy is missing,
  explicitly reimporting matching contents repairs it without losing position.
  Existing files with different contents are never overwritten by duplicate
  repair. A failed index write rolls back a newly copied file.
- Local page-number navigation for TXT and PDF. Decimal digits in different
  scripts are accepted, invalid/out-of-range input is rejected, and explicit
  jumps reuse the existing reader coordinators. TXT font size is persisted per
  book; UTF-16 resume positions clamp to whole composed characters. PDF restore
  positions clamp to the actual document's page bounds.
- A selectable **TextKit 2** paged TXT reader with previous/next controls and a
  page counter. Pages use measured line fragments at the actual container width,
  height, and scaled font; the page view does not scroll. Type-size controls,
  Dynamic Type changes, rotation, and split-view resizing trigger repagination.
- A **PDFKit** reader with native page gestures and zoom.
- **AVSpeechSynthesizer** for the text selection/remaining TXT or current PDF page;
  speech stops when leaving the reader or deactivating the app.
- A frozen reading-context panel. The shared-core scheme lists the existing KMP
  skills and explicitly prepares a bounded request locally through
  `InkflowCoreApi` → `AiContextAssembler`. Opening, selecting, reading, and restoring
  never invoke a provider.

This foundation does **not** implement EPUB, Android database or
sync compatibility, background speech, PDF OCR, AI provider configuration/sending,
notes/actions, or the full Android feature set. Scanned PDFs can be read visually;
speech/context require an existing text layer. Local storage is a native JSON
index and copied documents; it is not SQLite v22 or a portable Inkflow backup.

## Local preferences and language decisions

`AppPreferences` uses `UserDefaults.standard` with `inkflow.native.*` keys. The
library remains `Documents/NativeLibrary/library.json`; preferences do not enter
that index or a shared-core request. Wallpaper files live under
`Application Support/NativeAppearance/`. Reset Home clears its style, wallpaper,
motto and component choices while retaining theme and language. This is native
local persistence, not Android settings v5 backup/sync compatibility.

The selected Tab, library search, Home/Library scroll target IDs and Home/Me
settings paths also persist in the same local preferences boundary. Missing book
scroll targets and unknown routes are discarded. Changing Home style or hiding
an anchored component clears its obsolete target. Transient nil scroll updates
from disappearing views do not erase a saved target. Actual SwiftUI restoration
across iPad width/scene changes still needs runtime acceptance; storing and
validating that state is covered by host tests. No reader is auto-opened on
launch. Import reports are transient; successfully imported files/index entries
persist independently of the report.

The language resolver is explicit: CN/TW/HK/MO/SG map to zh-Hans, JP to ja, KR to
ko, and US/GB/AU/CA/NZ/IE to en. Other regions fall through to the enumerated zone
IDs in `AppPreferences.swift`. Unknown zones, arbitrary GMT offsets and broad
`Asia/*` prefixes are not guessed. Raw language tags are checked in order, with
`zh` variants mapped to the supported Simplified Chinese UI. The four languages
are declared in `Info.plist`; application copy is a typed four-column table.
App-owned errors retain their typed cause and resolve in the current interface
language when displayed, including after a language switch. The context panel's
initial sample question and PDF page label use localized interface copy; an
existing editable question is never rewritten on language change. Underlying OS
diagnostics and shared-core skill/preparation output retain their own language;
these are not translated by the UI resolver. Translation coverage
tests do not constitute native-speaker or device visual acceptance.

## TXT pagination and reading positions

`TextPageLayout` measures plain-text lines with `NSTextContentStorage`,
`NSTextLayoutManager`, and `NSTextContainer`. Measurement and rendering share the
same font, zero line-fragment padding, and usable size after reader insets.
Bounded lookahead measures enough source for each page without repeatedly laying
out the entire remaining book. Ranges cover the full source without overlaps or
omissions. Page boundaries respect composed character sequences, including ZWJ
emoji, combining marks, flags, and CRLF. An unusably small container still advances
by one whole cluster; that cluster may be clipped when even one line cannot fit.

The persisted position remains a UTF-16 reading anchor. Resizing or changing the
font selects the new page containing that anchor and saves the same offset;
explicit previous/next navigation moves it to the destination page start. A page
change clears the old selection and stops speech. Selection ranges are translated
from page-local coordinates back to full-source coordinates before speech or the
shared context bridge uses them. The shared visible boundary is the measured page
end, while the library stores the stable resume anchor. Listening retains the
existing selection-or-remaining-TXT behavior.

Pagination currently runs synchronously when the source, usable size, or scaled
font changes; normal selection and page turns reuse the page ranges. Large-file
latency, rotation, actual glyph clipping, native selection, VoiceOver, and speech
interactions still need broader device/simulator acceptance. The eight TextKit
XCTest methods now execute successfully on iPhone 17 and iPad (A16), iOS 26.4,
including actual line fit, reflow, selection coordinates and saved anchors. This
fixture coverage does not replace interactive/long-session acceptance; see
`VALIDATION.md`.

## Run the source-only app

Open `InkflowNative.xcodeproj`, choose **InkflowNative**, then an iPhone/iPad simulator
and Run. This scheme needs no Kotlin/Native installation and supports the readers,
imports, navigation/settings, bookshelf, and speech. Its context panel explicitly reports that shared
skills/preparation are unavailable; it has no replacement skill registry or prompt
pipeline.

```sh
xcodebuild -project ios-native/InkflowNative.xcodeproj \
  -scheme InkflowNative -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/inkflow-native-derived CODE_SIGNING_ALLOWED=NO build
```

For a physical device, select your signing team in Xcode. Imported documents live
under `Documents/NativeLibrary/`. A damaged index is left untouched rather than
silently reset; preserve its files before manually restoring/removing that index.

## Build and use the actual shared KMP framework

Coordinate this task with any running Android Gradle builds. Routine Android
builds do **not** register Native targets or download their toolchain. Opt in:

```sh
cd android
./gradlew :core:assembleInkflowCoreDebugXCFramework -PenableIos=true
# Production framework, when needed:
./gradlew :core:assembleInkflowCoreReleaseXCFramework -PenableIos=true
```

Kotlin Gradle plugin/Native version is 2.2.20. The first opt-in build downloads the
matching Native compiler/dependencies if missing. The static framework includes
`iosArm64` and `iosSimulatorArm64`; Intel simulator support is not included.
Debug output: `android/core/build/XCFrameworks/debug/InkflowCore.xcframework`.

The real 2.2.20 Debug XCFramework was built on 2026-11-28, and all app Swift
sources compiled/linked against both its ARM64 device and simulator slices.
`SharedCoreBridge.swift` required no API changes. The simulator executable contains
the actual Kotlin runtime and `InkflowCoreApi` symbols; no substitute header or
stub library is used. The SharedCore scheme now also builds and executes all
41 available XCTest methods on both iPhone 17 and iPad (A16), iOS 26.4 (23E244),
with zero failures or skips. See the newest section of `VALIDATION.md` for exact
commands, result bundles and the remaining acceptance limits.

To reproduce the simulator compile/link and XCTest source type-check without a
runtime, from the repository root:

```sh
ios-native/scripts/check_swift.sh /tmp/inkflow-native-shared-check shared-core
```

The optional `shared-core` mode requires the real Debug simulator binary, defines
`INKFLOW_SHARED_CORE` and links it with `libc++`. Omitting that argument retains
the source-only check. The Xcode SharedCore configuration selects the device or
simulator slice using SDK-specific framework paths for both app and test targets.

Then select **InkflowNativeSharedCore** (configuration **SharedCore**) in Xcode, or:

```sh
xcodebuild -project ios-native/InkflowNative.xcodeproj \
  -scheme InkflowNativeSharedCore -configuration SharedCore \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/inkflow-native-shared-derived CODE_SIGNING_ALLOWED=NO build
```

The SharedCore scheme requires the real framework and fails to link when it is
missing. It never silently falls back to source-only mode. Device builds use the
same XCFramework's device slice. No build phase invokes Gradle behind the scenes.

### Shared API and ownership

`InkflowCoreApi.readingSkills()` projects `ReadingSkillRegistry`. Explicit
`prepareReadingRequest(...)` validates registered skill/workload, authorization,
UTF-16 offsets, and visible selection end, then uses `AiRequest` and the existing
`AiContextAssembler`. Fractional progress is calculated from the captured
chapter/page and offset; only preceding visible source enters the prompt.
Preparation has no network or persistence side effects.

The returned request can later be sent with `InkflowCoreApi.execute(request,
gateway, onDelta)`, which delegates to **AiRequestGateway** for authorization,
provider routing, bounded fallback, token usage, and stream delivery. A future iOS
transport/provider settings implementation must plug into that gateway. This app
ships no transport and makes no claim of model sending. Task/evidence/mutation
features remain with the existing shared domain and need platform persistence
before being exposed here; the starter creates no parallel implementation.

## Tests and reproducibility

```sh
cd android
./gradlew :core:jvmTest --tests com.aceinandroid.inkflow.core.ai.InkflowCoreApiTest
cd ..
# With an installed simulator and the real Debug XCFramework:
xcodebuild -project ios-native/InkflowNative.xcodeproj \
  -scheme InkflowNativeSharedCore -configuration SharedCore \
  -destination 'platform=iOS Simulator,id=<available-device-UUID>' \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO test
```

The Kotlin JVM facade tests cover registry reuse, precise spoiler boundaries, invalid/unknown
requests, UTF-16 validation, and actual delegation through the existing gateway.
`LibraryStoreTests` covers sandbox copies, UTF-16 normalization, rejected formats,
PDF import/position persistence, and preserving a damaged index.
`TextPageLayoutTests` adds eight fixture/test methods for mixed CJK/Latin text,
long paragraphs, ZWJ emoji/combining marks/CRLF, lossless page coverage, small
containers, measured font/size changes, UIKit line fit, anchor lookup, actual
coordinator next/previous/selection behavior, and library resume persistence.
These tests require an installed simulator to execute; compilation alone does not
establish runtime layout correctness.

The new `AppPreferencesTests`, `HomeLibraryTests` and `LibraryRecoveryTests` use XCTest to cover manual
and automatic language precedence, raw language fallbacks, all preference choices,
unknown defaults, store isolation, motto preservation, old-index migration,
recency ordering, progress, and deletion. Recovery tests add real mixed-result
imports, UTF-8/BOM/UTF-16 deduplication, legacy digest migration, missing-copy
repair, rollback after index-write failure, duplicate-ID/path rejection,
per-book font restoration and an actual three-page PDF loaded through PDFKit on
the macOS host. Pure location tests validate page input, extreme bounds and
composed-character UTF-16 anchors. A further iOS-only coordinator test covers
explicit TXT jumps, stale queued callbacks and subsequent reflow; it has executed
on both simulator models above. Run the 25 Foundation-compatible tests against the real
Foundation/Combine/library sources on the macOS host even without an iOS runtime:

```sh
ios-native/scripts/test_foundation.sh
```

This builds an isolated temporary Swift package without external dependencies;
it does not execute SwiftUI, UIKit pagination, PDF rendering or speech. The iOS
test target contains 37 source-only-compatible methods plus four
`SharedCoreBridgeTests` methods enabled when the real InkflowCore module is
available. Those four execute the actual Kotlin registry, precise reading/spoiler
boundary, authorization/unknown-identifier rejection and UTF-16 validation, plus
the Swift bridge's local-preparation display. They neither configure a provider
nor call the gateway's execution method. All 41 passed on each simulator.

The checked-in Xcode project and shared schemes are maintained with xcodeproj 1.27:

```sh
ruby ios-native/scripts/generate_project.rb
```

It updates source membership and settings in `ios-native/InkflowNative.xcodeproj`,
preserving existing target/file IDs and shared schemes; missing projects/schemes
are created. Repeated generation is idempotent. No Flutter file is read or modified. `scripts/check_swift.sh` can compile/link the source-only Swift
app against the installed simulator SDK without launching a simulator. This is a
compiler check, not runtime acceptance or a substitute for `xcodebuild test`.
Run it from the repository root:

```sh
ios-native/scripts/check_swift.sh /tmp/inkflow-native-m7-check
```
