# Inkflow native iOS / iPadOS starter

This is a separate SwiftUI app, not the Flutter `ios/` runner. Requires Xcode 16+
(with the iOS platform and a simulator runtime installed), iOS/iPadOS 17+, and an
Apple Silicon Mac for the supplied simulator framework target.

Supported now:

- Import UTF-8 or BOM-marked UTF-16 `.txt`, and unlocked `.pdf` files from Files
  (up to 64 MB each). Files are copied into the app sandbox before access ends.
- A persistent local bookshelf with deletion and reading-position restoration.
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

This startup slice does **not** implement EPUB, Android database or
sync compatibility, background speech, PDF OCR, AI provider configuration/sending,
notes/actions, or the full Android feature set. Scanned PDFs can be read visually;
speech/context require an existing text layer. Local storage is a native JSON
index and copied documents; it is not SQLite v22 or a portable Inkflow backup.

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
interactions still need device/simulator acceptance. The current environment only
permits source compilation, not that runtime validation; see `VALIDATION.md`.

## Run the source-only app

Open `InkflowNative.xcodeproj`, choose **InkflowNative**, then an iPhone/iPad simulator
and Run. This scheme needs no Kotlin/Native installation and supports the readers,
imports, bookshelf, and speech. Its context panel explicitly reports that shared
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
# From repository root, with an installed simulator:
xcodebuild -project ios-native/InkflowNative.xcodeproj -scheme InkflowNative \
  -destination 'platform=iOS Simulator,name=<installed simulator>' test
```

The facade tests cover registry reuse, precise spoiler boundaries, invalid/unknown
requests, UTF-16 validation, and actual delegation through the existing gateway.
`LibraryStoreTests` covers sandbox copies, UTF-16 normalization, rejected formats,
PDF import/position persistence, and preserving a damaged index.
`TextPageLayoutTests` adds seven fixture/test methods for mixed CJK/Latin text,
long paragraphs, ZWJ emoji/combining marks/CRLF, lossless page coverage, small
containers, measured font/size changes, UIKit line fit, anchor lookup, actual
coordinator next/previous/selection behavior, and library resume persistence.
These tests require an installed simulator to execute; compilation alone does not
establish runtime layout correctness.

The checked-in Xcode project and shared schemes are generated with xcodeproj 1.27:

```sh
ruby ios-native/scripts/generate_project.rb
```

It only recreates the new `ios-native/InkflowNative.xcodeproj`; no Flutter file is
read or modified. `scripts/check_swift.sh` can compile/link the source-only Swift
app against the installed simulator SDK without launching a simulator. This is a
compiler check, not runtime acceptance or a substitute for `xcodebuild test`.
Run it from the repository root:

```sh
ios-native/scripts/check_swift.sh /tmp/inkflow-native-pagination-check
```
