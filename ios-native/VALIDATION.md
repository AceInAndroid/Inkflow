# M7 SharedCore iPhone/iPad runtime XCTest — 2026-11-28

**Actual simulator build and XCTest execution passed on both device models.**
This section supersedes earlier runtime-unavailable statements below. It is
bounded regression evidence, not full M7 feature/UI acceptance. The existing
installed iOS **26.4 (23E244)** runtime was used without another download. No
Android device, production source or Gradle setting was changed, and no user data
was deleted.

## Runtime scope and changes

- Created dedicated ARM64 simulators **Inkflow M7 iPhone / iPhone 17**
  (`75AD4C60-8B3A-466B-8F5E-6C6DA798E70E`) and **Inkflow M7 iPad / iPad (A16)**
  (`22439CA5-0334-4DA2-962E-EB544555CA17`), both iOS 26.4 build 23E244. Booted
  sequentially and reused one DerivedData directory; devices are retained for
  follow-up and shut down after verification.
- The existing **37 tests first passed on iPhone**, including the eight UIKit /
  TextKit methods that previously had only type-check evidence. No failures
  required a production-code change in this stage.
- Added four **real shared-core runtime tests** in
  `InkflowNativeTests/SharedCoreBridgeTests.swift` and refreshed the Xcode source
  membership. They execute the built Kotlin/Native registry, local request
  preparation, exact progress and spoiler boundary, explicit authorization /
  unknown identifier rejection, and unseen/split UTF-16 selection rejection.
  The Swift bridge's actual projections and local-preparation output are also
  exercised, preserving Japanese user input. They configure no transport and do
  not invoke `execute` or a model. The class is compiled only when the actual
  `InkflowCore` module can be imported; source-only/host checks have no substitute.
- The final full SharedCore suite is **41 tests on each simulator**, with **0
  failures, 0 skips and 0 expected failures**, confirmed independently using
  `xcresulttool get test-results summary` on both result bundles. No selection
  filter, disabled test or retry-until-pass option was used.
- Launched the real shared-core app after testing and inspected native screenshots
  of the Chinese classic Home: bottom Home/Library/Me tabs on iPhone, equivalent
  three-item sidebar on iPad. This is a root-screen launch smoke check only.
- Production Swift and shared bridge behavior are unchanged in this stage. Changes
  are the new test file, generated project membership, README and this record.

## Fresh verification

| Check | Exact result |
| --- | --- |
| iPhone 17 SharedCore `build`, then initial `test` | **BUILD SUCCEEDED; 37/37 tests passed.** Initial result `/tmp/inkflow-native-runtime-28-iphone-initial.xcresult`. |
| iPad (A16) SharedCore `build`, then complete `test` | **BUILD SUCCEEDED; TEST SUCCEEDED; 41/41 passed, 0 failed/skipped.** Result `/tmp/inkflow-native-runtime-28-ipad.xcresult`. |
| iPhone 17 final SharedCore `build test` | **BUILD SUCCEEDED; TEST SUCCEEDED; 41/41 passed, 0 failed/skipped.** Result `/tmp/inkflow-native-runtime-28-iphone.xcresult`. |
| `ios-native/scripts/test_foundation.sh` | **25 macOS XCTest cases passed, zero failures.** Executes actual Foundation/preferences/library/location sources; excludes UIKit and Kotlin/Native execution. |
| `ios-native/scripts/check_swift.sh /tmp/inkflow-native-runtime-28-swift-check shared-core` | **Passed:** all 12 app Swift sources compile/link against the real simulator slice; all 41 shared-suite XCTest methods type-check. Direct link retains the historical clang sysroot warning and exits zero. |
| Project regeneration, Ruby/Bash syntax, plist and tracked/new-file whitespace | **Passed.** 12 app / 6 test source memberships; generation idempotent, project SHA-1 `c0abad4892377a43ab762a3c6748200e4efffff8`. |

The complete simulator suite comprises 8 preferences, 5 language, 2 Home/library,
7 library recovery, 4 original library, 3 pure location, 4 shared bridge and
8 UIKit/TextKit tests. Existing actual PDF import/load fixtures execute PDFKit;
they do not exercise PDFView touch gestures. The invalid-PDF rejection fixture
prints a CoreGraphics PDF diagnostic while passing. Xcode also reports skipped
AppIntents metadata extraction because the app has no AppIntents dependency;
this is a build warning, not a skipped XCTest.

Exact final iPhone command, from the repository root:

```sh
xcodebuild -project ios-native/InkflowNative.xcodeproj \
  -scheme InkflowNativeSharedCore -configuration SharedCore \
  -destination 'platform=iOS Simulator,id=75AD4C60-8B3A-466B-8F5E-6C6DA798E70E' \
  -derivedDataPath /tmp/inkflow-native-real-core-runtime \
  -resultBundlePath /tmp/inkflow-native-runtime-28-iphone.xcresult \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO build test
```

For the recorded iPad checks the destination UUID was
`22439CA5-0334-4DA2-962E-EB544555CA17`; `build` and `test` were separate invocations,
and only `test` supplied `/tmp/inkflow-native-runtime-28-ipad.xcresult` as its
result bundle. Use a fresh result path when rerunning, as Xcode does not overwrite
existing bundles.

Evidence:

- Build/test logs: `/tmp/inkflow-native-runtime-28-iphone-build.log`,
  `/tmp/inkflow-native-runtime-28-iphone-initial.log`,
  `/tmp/inkflow-native-runtime-28-ipad-build.log`,
  `/tmp/inkflow-native-runtime-28-ipad-test.log`,
  `/tmp/inkflow-native-runtime-28-iphone-test.log`.
- Parsed results: `/tmp/inkflow-native-runtime-28-iphone-summary.json`,
  `/tmp/inkflow-native-runtime-28-ipad-summary.json`.
- Host/source logs: `/tmp/inkflow-native-runtime-28-foundation.log`,
  `/tmp/inkflow-native-runtime-28-swift-check.log`.
- Inspected root-screen images: `/tmp/inkflow-native-runtime-28-iphone.png`,
  `/tmp/inkflow-native-runtime-28-ipad.png`.

## Post-test disk cleanup — 2026-11-28 09:49 +0800

Both simulator test runs had finished before cleanup; neither xcodebuild nor
xctest was running. Only these session-generated, reproducible build directories
were removed (all under `/tmp/`):

- `inkflow-native-real-core-runtime` (the shared DerivedData directory).
- `inkflow-native-runtime-28-swift-check`.
- `inkflow-native-real-core-28-check`, `inkflow-native-real-core-28-device`,
  `inkflow-native-real-core-28-source-only`,
  `inkflow-native-real-core-28-existing-config`,
  `inkflow-native-real-core-28-derived`.
- `inkflow-native-m7-followup-check`.

Their measured allocated size totaled **200.21 MiB**. Observed filesystem free
space increased **194.04 MiB**, from 15,223,410,688 to 15,426,875,392 bytes
(**14.37 GiB free**); concurrent work can affect the filesystem-wide delta.
Exact paths, sizes and observations are retained in
`/tmp/inkflow-native-runtime-28-cleanup.json`. All XCTest result bundles, logs,
summary JSON and screenshots listed above remain. Both shutdown simulators and
their data remain (approximately 2.1 GiB iPhone / 1.9 GiB iPad). The installed
runtime, pre-existing/global caches, actual shared XCFramework and concurrent
Android jobs were left intact. Repeating the documented commands regenerates
the removed build outputs; cleanup did not rerun or invalidate recorded tests.

## Final batch handoff self-check — 2026-11-28

`git diff --check -- ios-native` and the corresponding staged-diff check passed.
All 11 untracked source/test/script files also passed trailing-whitespace and
final-newline checks. Inspection of all 28 files under `ios-native/` found no
build outputs, temporary logs, user-specific Xcode state, credential files or
matches for the checked private-key/provider-token/credential-assignment
patterns. Bash/Ruby syntax checks and Info.plist/project plist validation passed.
README now separates implemented capabilities from verified acceptance, and the
older records below are explicitly historical. This final review changed only
README/VALIDATION; the runtime and host results above were not rerun for these
documentation-only edits. Commit and push are left to the parent task.

## Remaining M7 acceptance

There is no longer a missing-runtime blocker for these two simulator destinations.
The passing tests do not cover full SwiftUI state restoration after process/scene
recreation or compact/regular changes, the file-provider picker, touch/selection /
PDFView gestures, speech/audio/background behavior, all four languages and four
Home styles visually, large type/VoiceOver, long-session/large-document performance,
or physical hardware. No full XCUITest interaction matrix was run. Reader skins,
EPUB, Android v22/settings v5 compatibility and backup/sync, notes/actions/undo,
full Me features, provider transport/tasks/coaching, release assembly, signing and
distribution remain as previously documented. Full M7 parity is not claimed.

---

# Historical validation records

The sections below preserve results and blockers at their respective stages.
Statements that the runtime was unavailable, the download was still running, or
shared-core linkage / iOS XCTest execution was unverified are historical and
superseded by the completed two-device verification at the top of this file.
They are not current blockers or instructions to download the runtime again.
Later results do not imply that the interactive acceptance gaps were closed.

# Historical stage: M7 real shared-core compile/link — 2026-11-28

**Real binary build and Swift linkage passed; UIKit/XCTest runtime is still
unverified.** This section supersedes older statements below that no actual
framework was available. Android production sources and Gradle settings were not
modified. Existing concurrent Android work was preserved; only ignored build
outputs were generated there. No other process was killed or stopped.

## Changes and integration evidence

- Ran the existing opt-in Gradle target after inspecting current Android build
  logs/processes. Kotlin/Native **2.2.20** was provisioned with its matching LLVM
  19 dependency using the normal download path. No alternate compiler, stub,
  header-only substitute or Gradle configuration workaround was used.
- Output: `android/core/build/XCFrameworks/debug/InkflowCore.xcframework`, containing
  real static archives for `ios-arm64` and `ios-arm64-simulator`, about 25 MB each.
  Both slices expose the current generated `InkflowCoreApi` interface.
- The old SharedCore search path pointed to the XCFramework's parent directory.
  Compiling with that path and `INKFLOW_SHARED_CORE` reproduced **no such module
  InkflowCore**. Fixed only the iOS linking setup: `generate_project.rb` and the
  generated project now select the correct slice using conditional SDK paths in
  both app and test configurations. Source-only configuration remains available.
- `check_swift.sh` now accepts an optional second argument `shared-core`; it
  requires the actual simulator archive, enables the existing bridge conditional,
  propagates the module search path to XCTest type-checking, and links InkflowCore
  and libc++. Also checked its source-only mode under macOS's bundled Bash.
- **No changes to `SharedCoreBridge.swift` or `SpeechController.swift` were
  necessary.** No AI request/provider invocation was added. All bridge calls
  remain at the existing explicit local-preparation boundary.

## Fresh commands and exact results

| Command / check | Result and limit |
| --- | --- |
| From `android/`: `./gradlew :core:assembleInkflowCoreDebugXCFramework -PenableIos=true --console=plain` | **BUILD SUCCESSFUL in 45s**, 6 actionable tasks executed: both Native compilations, both framework links and actual XCFramework assembly completed. Gradle deprecation warnings remain. This does not execute core tests. |
| `ios-native/scripts/check_swift.sh /tmp/inkflow-native-real-core-28-check shared-core` | **Passed:** all 12 app Swift sources compiled/linked against the real simulator slice with `-D INKFLOW_SHARED_CORE`; all **37 XCTest methods** type-checked with that module dependency. `vtool`: IOSSIMULATOR, minimum iOS 17.0, SDK 26.4. |
| Direct `swiftc` device command below | **Passed:** all 12 app sources compiled/linked against the real device slice. `vtool`: IOS, minimum iOS 17.0, SDK 26.4. Unsigned validation executable only; no device install/run/archive acceptance. |
| `nm` on both executables | **Passed:** each includes `_OBJC_CLASS_$_InkflowCoreInkflowCoreApi`, the Kotlin implementation and ObjC-to-Kotlin thunk for `prepareReadingRequest`, and `_Kotlin_initRuntimeIfNeeded`. This establishes actual static linkage beyond import/header type-checking. It does not execute those functions. |
| `ios-native/scripts/check_swift.sh /tmp/inkflow-native-real-core-28-source-only` | **Passed:** source-only compilation/link and all 37 XCTest source methods still type-check. Both link modes retain the existing clang sysroot warning, with exit zero. |
| `xcodebuild -project ios-native/InkflowNative.xcodeproj -scheme InkflowNativeSharedCore -configuration SharedCore -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/inkflow-native-real-core-28-derived CODE_SIGNING_ALLOWED=NO build` | **Blocked before compilation, exit 70:** no eligible destination; Xcode reports iOS 26.4 is not installed. This is not an Xcode scheme build pass. `-showBuildSettings` also cannot resolve a destination in the current state. |
| Project parser and generation, Bash/Ruby syntax, whitespace | **Passed:** both target configurations have the two appropriate slice paths. Generator is idempotent; project SHA-1 `22e34eea2fd8b126d4a2540f72c0934c53c38151`. |

Exact additional device check, from the repository root:

```sh
mkdir -p /tmp/inkflow-native-real-core-28-device
xcrun swiftc -sdk "$(xcrun --sdk iphoneos --show-sdk-path)" \
  -target arm64-apple-ios17.0 -D INKFLOW_SHARED_CORE \
  -F android/core/build/XCFrameworks/debug/InkflowCore.xcframework/ios-arm64 \
  -parse-as-library -enable-testing -module-name InkflowNative \
  -emit-module -emit-module-path /tmp/inkflow-native-real-core-28-device/InkflowNative.swiftmodule \
  -emit-executable -o /tmp/inkflow-native-real-core-28-device/InkflowNative \
  ios-native/InkflowNative/*.swift -framework InkflowCore -lc++
```

Archive SHA-256, at this build (future rebuilds may differ):

- Device: `8e7d6a97f37bf0dd2e2e3a4ea2c77bd4a7bbc3e5eedf32ce896c4fa0e0505d24`.
- Simulator: `1f6d533f0541f614f17fad163b76d746f348d59c496eaa64a1d3ad1007e9ef10`.

Logs: `/tmp/inkflow-ios-real-core-28-build.log`,
`/tmp/inkflow-native-real-core-28-existing-config.log`,
`/tmp/inkflow-native-real-core-28-check.log`,
`/tmp/inkflow-native-real-core-28-device.log`,
`/tmp/inkflow-native-real-core-28-source-only.log`,
`/tmp/inkflow-native-real-core-28-xcodebuild.log`,
`/tmp/inkflow-native-real-core-28-settings-sim.log`,
`/tmp/inkflow-native-real-core-28-artifacts.log`.

## Runtime handoff and remaining scope

The parent's already-running `xcodebuild -downloadPlatform iOS -buildVersion 26.4
-architectureVariant arm64` is left untouched. Its log is
`/tmp/inkflow-ios-runtime-28-download.log` (parent session 83007). No duplicate
platform download was started. At this stage's last check it was at 58.8%
(4.97 GB / 8.46 GB), with the original download process still running;
`simctl list runtimes` and `simctl list devices available` were empty.

After that download completes, first verify an available iOS 26.4 runtime and
actual iPhone/iPad simulator destinations. Then run the SharedCore scheme's real
build/tests against their device IDs, for example:

```sh
xcodebuild -project ios-native/InkflowNative.xcodeproj \
  -scheme InkflowNativeSharedCore -configuration SharedCore \
  -destination 'platform=iOS Simulator,id=<available-device-UUID>' \
  -derivedDataPath /tmp/inkflow-native-real-core-runtime \
  CODE_SIGNING_ALLOWED=NO test
```

No UIKit, PDFView, SwiftUI navigation, bridge execution or iOS XCTest runtime pass
is claimed here. The existing 37 methods primarily cover preferences, library and
pagination; native bridge behavior/authorization/spoiler execution on iOS still
needs dedicated runtime acceptance, beyond their successful app linkage. Release
XCFramework assembly, signing/distribution, provider transport and full M7 feature
parity also remain. Earlier Foundation test results are historical, not rerun in
this linking-only stage.

---

# Historical stage: M7 localization, restoration and local reading recovery — 2026-11-28

This follow-up still delivers **partial M7 foundation, not parity acceptance**.
The write set is `ios-native/` only. No Android/core edit, Gradle operation,
large platform/runtime installation, UIKit launch or automatic AI call was added.
The initial foundation and P3 records below are historical session results.

## Fixed gaps and concrete behavior

- Audited Home, Library, Me, Settings and reader/context chrome for English UI
  constructor literals: only the `Inkflow` brand remains. The sample question
  and PDF page label now use four-language strings. Existing typed question
  drafts, book content/titles, file names and custom mottos stay verbatim.
  App-owned library/reader failures now retain typed causes and localize at
  display time; wallpaper failures also resolve at display time. OS errors and
  shared-core output keep their original language. The audit is a source check,
  not native-speaker or device-layout acceptance.
- Selected Tab, search query, Home/Library scroll targets and both settings paths
  now have persistent, validated state. Unknown routes, deleted-book targets,
  obsolete style targets and hidden-component targets are rejected/cleared.
  Nil updates while views disappear do not clear the saved scroll target.
  The iPad optional sidebar binding cannot replace the selected Tab with nil.
  SwiftUI/iPad runtime restoration is still unverified; persistence/validation
  decisions execute in host XCTest.
- Batch imports process every file and report each success, existing duplicate,
  repaired copy or failure in a visible, dismissible Library results panel.
  Each successful result opens its real current library entry explicitly.
  Import no longer simultaneously requests a reader modal and an error alert;
  it does not mark books read. A failed file does not roll back successful peers.
- Import hashes normalized bytes with system CryptoKit SHA-256. UTF-8 BOM and
  BOM-marked UTF-16 versions deduplicate without rewriting book titles/content.
  Legacy entries without a digest are compared against bounded local contents
  during explicit import. A missing digest-bearing copy can be repaired by
  matching reimport while preserving ID, position, font and recency. A legacy
  entry whose file AND digest are missing cannot be matched safely; reimport
  creates a new entry. No filename/title guess is used to attach it to old data.
  Existing readable copies with different contents are not overwritten.
- Duplicate IDs and unsafe index paths enter preserved/read-only failure mode.
  Imports fail without touching that index; newly copied files roll back if an
  index write fails. Removal uses the actual indexed identity and canonical
  managed filename. Old valid indexes remain compatible; negative anchors/page
  indexes, invalid progress bounds and out-of-range font sizes are normalized.
- Real TXT/PDF loading now uses `LibraryStore` validation, with localized reader
  error/retry UI. TXT font size persists per book. Restore offsets clamp at
  composed-character boundaries through one Foundation helper shared with
  TextKit pagination. A new host regression exposed NSString splitting CRLF;
  resume bounds, pagination lookahead and tiny-container fallback now explicitly
  keep CRLF together, alongside emoji and combining-character clusters.
  PDF page restore clamps both bounds. Go-to-page validates
  Unicode decimal digits/range/overflow, then explicitly updates the existing
  TXT/PDF coordinator; it adds no reader identity reset or model invocation.
  Pending TXT page callbacks and PDF page notifications discard stale revisions.
- `SharedCoreBridge.swift` and `SpeechController.swift` are unchanged. Request
  preparation remains on the explicit button; import, route restoration,
  language changes, font changes and page navigation add no provider call.

## Fresh checks

| Command / check | Result and limit |
| --- | --- |
| `ios-native/scripts/test_foundation.sh` | **25 XCTest cases passed, zero failures on macOS.** Uses the actual preferences, localization, library and location sources. Includes persisted navigation/search/anchors, hidden/unknown targets, per-file partial import, normalized/legacy deduplication, missing-copy repair, write rollback, damaged index preservation, font/position restoration, a real three-page PDF import/load, page input and Unicode cluster bounds. PDFKit loading on macOS is not PDFView/UIKit runtime acceptance. |
| `ios-native/scripts/check_swift.sh /tmp/inkflow-native-m7-followup-check` | **Passed:** plist lint; all **12 app Swift files** compiled/linked for arm64 iOS Simulator; all **37 XCTest methods** source-type-checked. `vtool`: IOSSIMULATOR, minimum iOS 17.0, SDK 26.4. Existing clang sysroot warning remains, exit zero. No iOS XCTest execution. |
| English UI constructor audit of `HomeView.swift`, `NativeRootView.swift`, `ReaderView.swift`, `InkflowNativeApp.swift` | **Passed:** only `.navigationTitle("Inkflow")` remains as an English-leading literal. New labels/errors/sample question use the four-language table. This is a bounded source audit, not a translation-quality proof. |
| `ruby ios-native/scripts/generate_project.rb` twice; `xcodebuild -list -project ios-native/InkflowNative.xcodeproj` | **Passed:** 12 app / 5 test source memberships; both schemes retained. Consecutive project SHA-1: `a7cc71898c3456c7c0fec321f0a5c28d59407bf5`. |
| `xcodebuild -version`; `xcrun simctl list runtimes`; `xcrun simctl list devices available` | Xcode **26.4 (17E192)**; runtime and available-device lists still **empty**. No runtime installation attempted. The initial foundation's destination-selection failure still applies; no fresh UIKit run/build via an Xcode destination is claimed. |
| Shell/Ruby syntax, plist lint, tracked and new-file whitespace checks | **Passed.** |

Logs: `/tmp/inkflow-native-m7-followup-foundation.log`,
`/tmp/inkflow-native-m7-followup-check.log`,
`/tmp/inkflow-native-m7-followup-project.log`,
`/tmp/inkflow-native-m7-followup-ui-audit.log`.

## Remaining scope after this follow-up

- UIKit/SwiftUI runtime verification: compact/regular rebuilds and process/scene
  restoration, exact scroll placement, tab/settings return, import-result panel,
  file picker, jump/selection/speech interaction, font/rotation recovery, PDFView
  page gestures, VoiceOver, large type and four-language × four-style layouts.
  The TXT coordinator jump fixture is type-checked, not executed. The reader
  itself is not auto-restored/opened at launch; saved per-book locations are used
  only when the user opens a book. Import reports are transient; results already
  committed to the library remain persistent.
- Import, legacy duplicate comparison, hashing and pagination are still
  synchronous; large-library/64 MB document latency and memory need profiling.
  File-provider coordination, inbound share/deep-link routing and general
  corrupt-index recovery/export UI remain beyond this slice; no destructive
  reset or false repair control is exposed.
- EPUB, Android v22/settings v5 compatibility, backup/sync, grouping/bulk
  management, rich details, notes/actions/undo, full Me knowledge/statistics,
  independent reader skins, dual-page/AI workspace, advanced selection,
  mini/full/background narration, online TTS, AI tasks/evidence/coaching,
  long-reading performance, signing/distribution and full platform parity remain.
- Physical E-INK policy, crop editor and native-speaker translation review remain.
  The actual SharedCore framework build/link is still unverified and was not
  attempted while the parent works on Android. No new bridge implementation or
  fallback provider path was introduced.

---

# Historical stage: M7 iOS/iPadOS foundation validation — 2026-11-28

**Foundation only; full M7 parity is not delivered or accepted.** Changes in this
lane are confined to `ios-native/`. No Gradle/Kotlin toolchain operation was run;
concurrent Android changes are outside this lane. The P3/pagination records below
are historical evidence and were not rerun unless explicitly listed here.

## Delivered source behavior

- Compact Home/Library/Me tabs and regular-width iPad sidebar; Home and Library
  use the same full-screen TXT/PDF reader. No reader `.id` is tied to style,
  theme or UI language. The existing import copy/normalization, TextKit paging,
  PDFKit, speech lifecycle, and `SharedCoreBridge` boundaries are retained.
- Four Home styles use a single index-only `LibraryHomeSnapshot`: real featured
  and recent books, progress and opened/completed counts. No text scans, model
  calls, fabricated time totals or remote telemetry were added. New optional
  `lastReadAt` metadata round-trips; old indexes still decode and retain anchors.
  Deleting books removes them from the next Home projection.
- Local persisted style/component/motto/wallpaper/theme/language settings. Home
  reset leaves language/theme intact. A locally imported wallpaper has a size
  limit, is sandbox-copied/normalized, can be removed and has a missing-file
  fallback. E-INK Home uses static black/white line styling; this is a style,
  not a claim of hardware E-INK refresh support. The clock updates by minute
  only while Home is visible and the scene is active. Minimal Home intentionally
  hides recent/history panels while keeping navigation/settings available.
- en/zh-Hans/ja/ko interface strings, including reader controls/context-panel
  chrome. Automatic: supported region → exact enumerated IANA zone → first
  supported raw preferred language → English; explicit choice overrides all.
  No broad timezone-prefix/offset guesses or Bundle-negotiated language input.
  Default mottos follow UI language; custom mottos, titles, book contents,
  question text, voice selection and shared-core output remain unchanged.

## Fresh checks and exact limits

| Command / check | Result |
| --- | --- |
| `ios-native/scripts/test_foundation.sh` | **Passed: 12 XCTest cases, zero failures**, executing on macOS against the actual preferences, interface table, and library sources. Includes all four manual language overrides; conflicting region/zone/language precedence; unknown zone/offset rejection; raw zh-Hant/underscore/case tags and English fallback; complete string-table entries; every style and language persistence; custom motto preservation; default/unknown-key behavior; device-store isolation; legacy index migration; real import/recency/progress/deletion. These are host tests, not iOS UI tests. |
| `ios-native/scripts/check_swift.sh /tmp/inkflow-native-m7-check` | **Passed:** plist lint; all 11 app Swift files compiled and linked for arm64 iOS Simulator; all **23 XCTest methods** type-checked. `vtool`: IOSSIMULATOR, minimum iOS 17.0, SDK 26.4. Existing clang sysroot warning persists; exit was zero. No iOS test execution. |
| `ruby ios-native/scripts/generate_project.rb` (twice) | **Passed:** project parses, 11 app sources and 4 test sources have correct target membership; original target/file IDs and both schemes retained. Consecutive generated project SHA-1: `d72a1637c42c00067d28e30c2dd60c8eae2578cd`. |
| `xcodebuild -list -project ios-native/InkflowNative.xcodeproj` | **Passed:** app + test targets, Debug/Release/SharedCore configurations, both schemes. |
| `xcodebuild -project ios-native/InkflowNative.xcodeproj -scheme InkflowNative -configuration Debug -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/inkflow-native-m7-derived CODE_SIGNING_ALLOWED=NO build` | **Blocked before compilation, exit 70:** no eligible destination; Xcode reports **iOS 26.4 is not installed**. |
| `xcodebuild -version`; `xcrun swiftc --version`; `xcrun --sdk iphonesimulator --show-sdk-path`; `xcrun simctl list runtimes`; `xcrun simctl list devices available` | Xcode **26.4 (17E192)**, Apple Swift **6.3**; simulator SDK 26.4 exists. **No simulator runtimes or available devices.** No component installation attempted. |
| `plutil -lint ios-native/InkflowNative/Info.plist`; `bash -n ios-native/scripts/check_swift.sh ios-native/scripts/test_foundation.sh`; `ruby -c ios-native/scripts/generate_project.rb`; `git diff --check -- ios-native` | **Passed.** |

Fresh logs: `/tmp/inkflow-native-m7-foundation.log`,
`/tmp/inkflow-native-m7-check.log`, `/tmp/inkflow-native-m7-project.log`,
`/tmp/inkflow-native-m7-xcodebuild.log`.

## Remaining M7 / acceptance scope

- **iOS/iPadOS runtime acceptance remains blocked by missing platform/runtime.**
  Install it, then execute README `xcodebuild ... test` on iPhone and iPad.
  Neither the existing 11 UIKit/PDF/pagination tests nor the new tests have run
  on iOS in this lane. No simulator/device screenshot, VoiceOver, native menu,
  wallpaper rendering/crop, import picker, rotation/split view, large type,
  full-screen reader return/scroll restoration, manual/system locale change
  during reading, visual theme matrix or speech interaction is accepted here.
  Same-width navigation stays mounted behind the full-screen reader; exact
  scroll/navigation restoration across compact/regular reconstruction needs
  runtime verification and potentially additional restoration state.
- Four interface tables are present and nonempty; native-speaker review and the
  four-language × four-style × phone/iPad × large-type visual matrix remain.
  Underlying OS errors and shared-core skill/preparation diagnostics may use
  their original language. No automatic translation is applied to those outputs.
- Library search/import/single deletion and a responsive grid are implemented;
  grouping, multiselect/bulk management, rich book details, deep-link/external
  opening restoration, full knowledge/statistics areas in Me, reading-time
  history and explicit quote-to-motto actions remain. Wallpaper has fit-to-fill
  rendering, replacement/removal and fallback; an interactive crop editor is
  not implemented. The E-INK style does not detect/control physical E-INK panels.
- The existing local JSON library is **not** Android v22; preferences are **not**
  settings v5 backup/sync. EPUB, cross-device compatibility/sync, note/action/undo
  persistence, five independent reader skins, wide dual-page/AI workspace,
  advanced selection, mini/full narration player, background/online TTS, full
  AI tasks/evidence/actions/coaching, long-session/performance and release
  signing/distribution parity are still outside this foundation.
- No real `InkflowCore.xcframework` binary is present in the existing build output.
  `SharedCoreBridge.swift` is unchanged. The SharedCore configuration remains
  explicit; no replacement registry/provider/prompt path was added. No new KMP
  build, SharedCore conditional type-check or actual shared-framework link is
  claimed; the older header-only results below are not fresh verification.

---

# Historical stage: P3 startup validation — 2026-11-26

These checks apply to this starter, not feature parity or device acceptance.

| Check | Result |
| --- | --- |
| `cd android && ./gradlew help` | Passed after replacing the Kotlin-only `XCFramework` factory import with Groovy `XCFrameworkConfig`. |
| `:core:tasks -PenableIos=true -Pkotlin.native.home=<installed 2.3.0> --all --offline` | Passed; both ARM64 target link tasks and debug/release XCFramework assembly tasks registered. This is configuration validation with a local toolchain override, not a 2.2.20 framework build. |
| `:core:jvmTest --tests com.aceinandroid.inkflow.core.ai.InkflowCoreApiTest --offline` | Passed: 4 tests, zero failures/errors. |
| `scripts/check_swift.sh` | Passed: all source-only Swift sources compiled and linked; Mach-O load command confirms arm64 `IOSSIMULATOR`, minimum iOS 17.0, SDK 26.4. Eleven XCTest source methods type-checked (four library + seven pagination fixtures). No XCTest execution. |
| Shared-core Swift sources with `-D INKFLOW_SHARED_CORE` | Passed type-checking against the real ObjC header generated by the installed Kotlin/Native 2.2.0 compiler. The generated header is temporary; it is not a stub or a linked framework. |
| Xcode project parser | Passed: app/test targets, Debug/Release/SharedCore configurations, two checked-in shared schemes. |
| Android JA/KO resources | Final integration expanded both locales to all 535 base strings. XML key coverage and format placeholders match; strict Android build/lint and device smoke results are recorded in `android/NATIVE_MIGRATION_PLAN.md`. |
| `git diff --check` | Passed. |

## TXT pagination source checks — 2026-11-26

This follow-up changed only `ios-native/`; no Gradle task, Android change, runtime
installation, or toolchain download was performed. Earlier Android/KMP results in
the table above are retained session records, not rerun claims.

- `scripts/check_swift.sh /tmp/inkflow-native-pagination-check`: source-only app
  compiled and linked for arm64 iOS Simulator; all eleven XCTest methods
  type-checked. The new seven fixture/test methods cover page coverage and Unicode
  cluster boundaries, empty/tiny containers, measured font/container changes,
  independently rendered UIKit page line fit, offset lookup, coordinator
  resize/font/next/previous/global-selection behavior, and library anchor reload.
- Shared-core source type-check with `-D INKFLOW_SHARED_CORE` also passed after
  pagination integration, using the existing real generated ObjC header under
  `/tmp/inkflow-p3-native-check`. No framework binary was built or linked.
- A temporary macOS AppKit adaptation of the same measurement helper executed
  two smoke fixtures: mixed CJK/Latin (8 pages, 2,800 UTF-16 units) and composed
  emoji/combining marks/CRLF (15 pages, 3,100 units). Both had complete, contiguous
  cluster coverage. This checks the shared TextKit algorithm on macOS; it does
  **not** validate UIKit rendering or replace iOS XCTest execution.
- The checked-in Xcode project was regenerated to include `TextPageLayout.swift`
  and `TextPageLayoutTests.swift`; project parsing confirms both target memberships.
- TXT page state reuses the existing library, speech controller, and context
  bridge. Repagination retains the original UTF-16 anchor; user page turns save
  the destination page start. Page selection maps to full-source UTF-16 offsets.
- Whitespace checks included all 16 iOS source/project/documentation files using
  `git diff --no-index --check` against `/dev/null` (the starter files are currently
  untracked); no whitespace diagnostics.
- **No pagination XCTest was executed.** Measured line fit, cluster coverage,
  rotation/font resume, native selection, accessibility, and speech interactions
  remain runtime acceptance gaps. In particular, synchronous whole-book page-index
  generation has not been profiled on large TXT files.

Pagination source-check logs: `/tmp/inkflow-native-pagination-check.log` and
`/tmp/inkflow-native-pagination-shared-check.log`. The temporary AppKit smoke source
is `/tmp/inkflow-pagination-appkit-smoke.swift`.

## Historical environment blockers (superseded above)

- `xcodebuild ... -destination 'generic/platform=iOS Simulator' ... build` could
  not select a destination: Xcode reports the iOS 26.4 platform component is not
  installed. Simulator SDK 26.4 is present, but `simctl` lists no available
  devices/runtimes. No simulator launch, physical-device run, or XCTest run is
  claimed. Install the platform/runtime in Xcode Settings → Components and rerun
  the README commands.
- The default 2.2.20 Gradle XCFramework attempt was cancelled after over two
  minutes without advancing past task-graph configuration. It was not allowed
  to hold up Android validation. An offline attempt using an already-installed
  Native compiler reached ObjC export but could not link because
  `llvm-19-aarch64-macos-essentials-74` is missing. No XCFramework binary or
  shared-core app link is claimed. Provision the matching 2.2.20 Native
  dependencies, then run the documented opt-in Gradle assembly and SharedCore
  Xcode scheme.
- Direct Swift linking emitted a toolchain sysroot warning; it exited zero, and
  `vtool` independently confirmed the resulting platform as `IOSSIMULATOR`.

Logs used during this session are under `/tmp`: `inkflow-p3-gradle-help.log`,
`inkflow-p3-ios-configure.log`, `inkflow-p3-core-tests.log`,
`inkflow-native-swift-final.log`, `inkflow-native-shared-swift-typecheck.log`,
`inkflow-native-ios-build.log`, and `inkflow-p3-native-check/compile.log`.
