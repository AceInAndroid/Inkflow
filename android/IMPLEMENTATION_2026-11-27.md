# Inkflow implementation and verification — 2026-11-27

This is an implementation checkpoint for the approved M0–M7 plan, not an acceptance certificate for every milestone. Earlier November 26 reports are historical baselines; they do not certify these changes. Device tests in this report use development APKs.

Current native implementation commit: `dcc3606d`. Final production/test build: **integration24**. The final complete API31 standard suite reports **262 passed / 21 skipped / 0 failed**; API34 fullOcr reports **266 passed / 17 skipped / 0 failed**. The final results below supersede earlier intermediate failures without deleting their diagnostic history. Portable test counts, APK hashes and skipped method identities are recorded in [verification.json](validation/2026-11-27/verification.json).

## Delivered code and boundaries

- Removed the retired cloud sync implementation, account UI, functions, deployment settings, resources and automatic calls across the repository. WebDAV, self-hosted HTTPS progress sync and ZIP remain. Flutter preference export/import now permits supported keys and dynamic patterns, filters unknown values and nested credentials, and preserves local credentials only for unchanged service targets. No remote account or Git-history mutation.
- Added four product specifications and updated the native architecture/status links. Main navigation is Home / Library / Me, switching to a rail at 600dp. Four home styles share real local data, private wallpaper fallback, local motto and language settings. Library has adaptive columns, selection management and full-screen detail actions. WiFi transfer is reachable from an empty home and the library import menu.
- Added explicit generation scope authorization, independent ephemeral whole-book viewing, one bounded same-provider schema correction, source revalidation and checkpoint preservation. Ordinary reading and UI changes remain outside provider invocation. Agents/Skills reuse the registered request gateway. Five Agents are available; the unfinished coach Agent stays disabled.
- Added strict JSON/Markdown Skill import, preview/edit/copy/explicit overwrite, export, a WiFi pending-confirmation inbox, v5 ZIP compatibility and a Skill branch on the existing WebDAV protocol. Device defaults, single-book bindings, language, wallpaper and hardware mode are excluded from portable settings. Backup v1–v4 remains readable; no database version/table change.
- Added pagination fingerprints/disk cache invalidation, real single/spread layout, persistent annotation layers and transient highlight priority. Page reflow updates geometry without rewriting reading progress. Selection uses a single-consumer gesture session, handles, ordinary-screen magnifier and optional edge extension, default off and unavailable in E-INK. Complex link/footnote hit-testing and all renderer matrices still need acceptance.
- Added full-screen narration controls, independent read/playback positions, real range/paragraph callbacks, chapter continuation and explicit follow recovery. User browsing pauses following while audio can continue. Notification and player share playback state. Real-device lockscreen/Bluetooth and complete audio UI acceptance remain open.
- Added independent inherited/CJK/Latin fonts in paged and scrolling renderers, including imported-font identity in cache keys and v5 settings.
- Added explicit local reading goals, verified-selection difficulties and source-verified memory-card review with journaled 30-day conflict-safe undo. The local practice UI is reached from Book Hub records. These do not enable an AI coach. Memory-card incremental sync still needs a shared protocol extension.
- Added successful-request usage journaling at the existing gateway boundary and a local eight-dimension usage view. Legacy usage is frozen before migration, preventing aggregate double-counting. Reported, estimated and mixed/unknown counts remain separate. Failed-attempt and retry accounting gaps, limited retention and absent pricing are explicit; no prompt, key or response body enters the journal.

## Evidence collected in this run

### Build integration6

`/tmp/inkflow-integration-6.log`: core JVM **216**, standard JVM **164**, fullOcr JVM **164**, all without failure/error/skip. Both APKs and standard instrumentation APK built successfully. Both lint runs: **0 errors, 0 MissingTranslation**, **137 total issues each** (warnings/information remain). No translation exemption added.

This build precedes the local practice UI, translated splash, and final test-fixture updates; the final source requires a fresh build below.

### Device integration6 / test APK7

- API 31: isolated `emulator-5582`, SDK31 arm64 emulator.
- API 34: isolated `emulator-5580`, SDK34 arm64 emulator.
- Both targeted runs executed 58 tests, including font persistence/backup, coach action authorization and undo, usage journal and real spread/highlight drawing.
- API31 had **2 failures** (home fixture and bookshelf selection), API34 **3 failures** (same plus initial reader body readiness). One installed-book layout case was explicitly skipped in each. These are not green-suite results.
- Native reader lifecycle cases passed after removing persistence from the layout-reflow callback. This includes the previously failing exact EPUB CFI/progress recreation path.
- Initial Home test APK6 crashed because its isolated Context omitted ActivityResultRegistryOwner. That was a test harness defect, corrected before APK7. APK7 revealed locale/dialog-state fixture issues; the fixture now selects language through the real controller and waits for dialog closure. Pending fresh results are not treated as passes.
- A bookshelf fixture originally deduplicated identical files; distinct bodies now prove two imported IDs. Remaining selection/UI timing behavior is being investigated.

### Flutter cleanup

`/tmp/anx-cloud-removal-tests.log`: 39 passed. `/tmp/anx-m0-prefs-final.log`: 11 passed, with 5 overlapping the earlier suite (45 distinct targeted cases). Full analyzer: 0 errors, 0 warnings, 29 informational findings; analyzer exit status 1 is retained. Localization regeneration was included in the cleanup verification. These source files have not changed after their tests.

### User EPUB, local model and speech

- User-specified Naval EPUB: API34 loopback transfer test passed (TCP upload twice, SHA-256 identity, duplicate deduplication, real spine/TOC, search and exact CFI restoration). This is not physical WiFi acceptance.
- Physical Android API29 at the previously connected test endpoint: earlier in this implementation, saved encrypted OpenAI/Anthropic configurations both passed the actual gateway diagnostic; three real system-TTS tests passed (4 instrumentation methods total). Credentials were not exported to source/logs.
- Latest attempt to reconnect that API29 endpoint timed out. Another discovered Android endpoint rejected ADB authentication. No device was cleared or reconfigured to bypass authentication. Fresh physical WiFi, audio hardware and new-build validation remain unavailable until an authorized device is connected.
- Full-book generation and the required two-hour reading run are still open. Prior short/live tests do not prove those requirements.

## Remaining plan work and release gates

M0 code and formal specifications are present. M1/M2/M3/M4 contain substantial implementation but are not all accepted. M5/M6 are partial; M7 still has the original native iOS starter rather than Android feature parity.

Open gates include comprehensive AI stream/QueryBack UI, actual handles/magnifier/edge interaction, full reader menu/bookmark/animation behavior, table reinsertion/formula fallback, live generation scope matrices, preference learning and auto-reading coordination, memory-card protocol sync, iOS/iPadOS parity, small/wide/dark/E-INK UI review, physical speech/Bluetooth, two-hour reading, and the required performance comparison. Any new benchmark without a same-device pre-change baseline must not claim a percentage improvement or regression.

No skipped, unavailable or failing item is counted as a pass. The APKs are development builds, not an accepted stable release.

## Subsequent integration11

`/tmp/inkflow-integration-11.log`: BUILD SUCCESSFUL (26 seconds), including the practice UI, translated splash, Skill compatibility repairs and bounded HTTP rejection close. Core JVM **216/216**, standard JVM **177/177**, fullOcr JVM **177/177**, no errors/failures/skips. Both APKs and test APK rebuilt. Both lint reports contain **138 total issues, 0 errors, 0 MissingTranslation**. A separate four-language resource check found **800 base translatable keys, 0 missing keys/duplicates/format mismatches** at that source checkpoint.

Integration9 caught an actual transfer regression (TCP reset when rejecting the 21st upload before consuming its body). It was fixed by finishing the rejection response with bounded shutdown/drain; `/tmp/inkflow-transfer-rejection-10.log` passed all 7 transfer unit methods. The added case proves rejected uploads receive an HTTP response and the next valid upload remains usable. The rejected body is bounded by both time and bytes.

Targeted instrumentation against integration11 is in progress; its final outcomes will be appended, including failures. Later source edits require a corresponding new build.

## Commits already pushed

- `a021f130` — remove obsolete cloud implementation and restrict portable Flutter preferences.
- `c0807bc5` — shared scoped AI, Skills, cost/coach action types and reader interaction contracts/tests.

These commits do not assert completion of all later native UI milestones.

## Integration11 targeted device results

API31 and API34 each finished **31 methods, 2 failures** (`/tmp/inkflow-targeted-11-api31.log`, `/tmp/inkflow-targeted-11-api34.log`). Home's 16 language/style combinations, initial reader volume navigation, localized screens, vocabulary/workspace and Skill backup/sync cases passed. The two remaining failures are bookshelf select-all count and the practice dialog invalid-budget button state; both remain under investigation and are not accepted. Later security/UI fixes require a fresh test APK.

Remote `main` was checked against GitHub after continuation and points to `c0807bc5`.

## Home and bookshelf follow-up (APK14)

Both API31 and API34 passed all **3 targeted UI methods** (`/tmp/inkflow-home-shelf-14-api31.log`, `/tmp/inkflow-home-shelf-14-api34.log`): actual long-press selection, select-all, cancel/confirm batch deletion and file removal, separate detail read/listen callbacks, plus the 16 language/style combinations. The selection failure was a stale accessibility-node cache: callback/recomposition both had two selected IDs and the failure screenshot visibly showed “2 selected”, while the queried tree retained one. Test queries now refresh nodes (and clear the public API34 cache); product selection behavior was not weakened or bypassed. Temporary probes were removed.

Fresh 16-shot API31 Home capture was pulled to `/tmp/inkflow-home-14-api31` and visually reviewed as `/tmp/inkflow-home-14-contact.png`: dialog animations are no longer present, all four localized empty-library styles show the import/WiFi actions and E-INK uses static outlines. This is a normal-phone empty-state check, not small/wide/dark/wallpaper-image or populated-library acceptance.

## Integration15: request preflight and source authorization

`/tmp/inkflow-integration-15.log`: BUILD SUCCESSFUL (38 seconds). Core **225/225**, standard **177/177**, fullOcr **177/177**, zero failures/errors/skips; both APKs, instrumentation APK and both strict lint tasks passed. Each lint report still has **138 findings, 0 errors, 0 MissingTranslation**. Direct resource validation: **803 base translatable keys; Chinese/Japanese/Korean each 0 missing, duplicate or format mismatches**.

On each API31/34 AVD, all **9 gateway-preflight** and **13 generation** device methods passed, covering actual persisted task identity/state/revision, allowed generation types and scopes, canonical source/chunk/hash, pre-transport Evidence filtering, revocation/source change between keys, one schema correction, checkpoint and legacy reconfirmation behavior. A constructor integration review then caught the default service missing its BookStore when creating the gateway; the call is corrected and a real constructor/local-transport regression is being added. That later edit requires a fresh build.

The same targeted invocation ran 29 methods total: API31 had 3 failures (all new Skill UI fixture setup), API34 had those 3 plus one practice asynchronous-save fixture failure. These are not green suite results. Canonical-path and persisted-save waits are being repaired; all invalid-input/no-write assertions remain. Logs: `/tmp/inkflow-security-practice-15-api31.log` and `...-api34.log`.

## Actual reader performance sample (integration15, API31)

Opt-in `NativeReaderPerformanceTest` passed in **263.297 seconds**, performing **20 real bookshelf opens and 100 hardware page turns** on the 100,000-character single-chapter fixture. Every open restored persisted offset **2000** and the same visible page; every turn advanced the actual visible character offset. Existing book positions and AI usage remained unchanged; fixture and temporary preferences were cleaned up. Raw device/build/memory/position samples are preserved in [reader-performance-api31.json](validation/2026-11-27/reader-performance-api31.json).

Measured end-to-end UI-semantics timings: first fixture open **3902 ms**; subsequent 19 opens p50 **2779 ms**, p95 **2853 ms**; 100 turns p50 **1714 ms**, p95 **1869 ms**. This is an instrumented debug arm64 emulator at 392dp width with 28sp / 2.2 line spacing, not a release frame-time measurement. First fixture open is not a process-cold baseline. There is no same-device pre-change measurement, so no improvement or <10% regression claim is made; the comparative performance gate remains open.

## Integration16 and integration17 checkpoints

Integration16 built successfully in 41 seconds. **All 10 preflight methods** (including the production constructor → actual isolated HTTP/SSE → checkpoint path) and **all 4 practice methods** passed on both API31/34. Two Skill UI methods also passed, including rejecting approval after an existing conflicting definition is edited. One Skill startup case failed before the screen hierarchy was ready; its wait now yields for an actual target/scrollable tree instead of exhausting scroll attempts against an empty root.

Integration17 built successfully in **26 seconds**: core **225/225**, standard **191/191**, fullOcr **191/191**; both APKs and instrumentation APK built, lint **139 findings each, 0 errors and 0 MissingTranslation**. The extra warning is the existing lint suggestion to use the KTX SharedPreferences edit helper in the new brightness store; no exemption was added. Full **281-method** standard instrumentation runs have started on both API31 and API34; final results below must be read before acceptance. FullOcr instrumentation APK also built successfully in 11 seconds.

The reader now has a device-local, 5%–100% brightness control under its More menu and a follow-system choice. Window changes are live and restore the exact previous brightness on reader disposal; system settings/permissions and portable settings are untouched. Its integration and device acceptance are pending the current full run. Footer progress now derives from the real visible character anchor instead of marking every page in the last chapter as 100%; reflow and empty spread leaves do not advance it.


## Integration17 completed device suites

The standard full suite completed on both isolated AVDs, **281 methods each, zero failures**:

| Device | Passed | Assumption-skipped | Log |
|---|---:|---:|---|
| API31 / emulator-5582 | 260 | 21 | `/tmp/inkflow-full-17-api31.log` |
| API34 / emulator-5580 | 262 | 19 | `/tmp/inkflow-full-17-api34.log` |

The skipped methods include opt-in live provider/real-book/performance checks, unavailable TTS engines/fonts, standard-flavor scanned OCR and API-specific LocaleManager cases. They are not counted as passes. The previously failing lifecycle, Home, bookshelf, practice and Skill UI cases now pass in these fresh complete runs. All five brightness device tests pass; footer geometry/progress is covered by the 191-method JVM suites.

FullOcr API34 targeted language/screens/notification/preflight/Skill UI: **25 passed, zero failures/skips**, `/tmp/inkflow-fullocr-target-17-api34.log`. Its PDF search suite additionally passed **2/2**, including real image-only English/Chinese on-device OCR and selectable PDF text mode (`/tmp/inkflow-fullocr-pdf-17-api34.log`).

A separate **360dp, font scale 1.3, system dark** run had **1 pass (four Home styles) and 4 failures**. All four localized-screen methods attempted to click Settings below the Me page fold without scrolling. This run is retained as a failed acceptance attempt; a real scrolling harness and fresh rerun are required below. These counts do not certify the small-screen matrix.

Independent review also identified an interrupted Skill-import recovery case: a successful first commit changes its own conflict preview on restart. Checking that preview version before the durable incomplete receipt hid the uncertainty message behind repeated stale-preview errors. Incomplete receipts now take priority, preserving at-most-once behavior; a regression uses the actual refreshed pending entry after restart and verifies no extra copy or overwrite. Final verification follows below.


## Integration18 checkpoint

`/tmp/inkflow-integration-18.log`: BUILD SUCCESSFUL in **26 seconds**, core **225**, standard **192**, fullOcr **192**, all zero failures/errors/skips. Both production APKs and both instrumentation APKs built; both lint reports contain **139 findings, 0 errors, 0 MissingTranslation**. This includes the interrupted-confirmation fix and its 13-method inbox regression suite. A later review found legacy Skill-backup fallback and repeated two-device conflict-copy defects; those fixes require the next integration below.

The first small/dark rerun (`/tmp/inkflow-small-dark-18-api31.log`) again had **1 pass and 4 failures**: some Me/Settings labels remained unreachable to the first scrolling helper; the Korean case reached Settings but the Reading group was below its fold. A screenshot confirms actual dark styling and wrapping of Korean text at 360dp / font scale 1.3. This is incomplete compact-screen acceptance, with a further harness fix pending.

Opt-in specified-book acceptance (`/tmp/inkflow-specified-book-ui-18-api34.log`) passed **1/1 in 4.209 seconds**, using the user-provided EPUB in private cache: two real loopback HTTP uploads, byte/hash equality, single duplicate identity, spine/TOC/search, exact CFI, **actual native-paged ReaderScreen with matching visible source**, unchanged persisted chapter/CFI through close and unchanged model usage. Private screenshot: `files/specified-book-validation/reader.png`, copied for local visual review to `/tmp/inkflow-naval-reader-18-api34.png`. Source cache was removed by test cleanup; the EPUB and its screenshot are not repository artifacts.

An attempted host upload through ADB forwarding failed because the production listener binds the emulator's Wi-Fi-interface address, whereas ADB forwards to loopback. This transport setup failure is not a physical Wi-Fi result and is not counted as a pass. The subsequent explicitly loopback run above validates the software HTTP/import/reader path only. Fresh physical Wi-Fi/TTS/Bluetooth acceptance remains open.


## Integration19: compact/wide and data recovery

`/tmp/inkflow-integration-19.log`: BUILD SUCCESSFUL in **32 seconds**; core **225**, standard **200**, fullOcr **200**, zero failures/errors/skips. Both APKs and both test APKs built; both lint runs have **140 findings, 0 errors, 0 MissingTranslation**. New lint findings remain KTX editing suggestions; no suppression or translation exemption was added.

- **API31 standard, 360dp, system dark, font scale 1.3:** **20/20 passed**, zero skips/failures, 52.167 seconds (`/tmp/inkflow-compact-19-api31.log`). Includes all four localized-screen methods, all 10 Skill backup cases, incremental Skill sync and all three inbox UI flows.
- **API34 fullOcr, 960dp, light, font scale 1.0:** **28/28 passed**, zero skips/failures, 56.216 seconds (`/tmp/inkflow-wide-19-api34.log`). Same data/UI cases plus eight actual spread/highlight rendering methods.
- Screenshots for all four languages on Library/Settings/Statistics/Reader/AI were pulled to `/tmp/inkflow-compact-19-screens` and `/tmp/inkflow-wide-19-screens`; the dimension-suffixed files distinguish these new captures from retained normal-phone images. Page checks now perform real scrolling with refreshed accessibility descendants and window roots; the earlier compact failures were not waived.
- Legacy Skill definitions are exported through the existing `NativeAiStore.exportStoredSkills()` compatibility boundary. The backup path may read exactly the legacy `custom_skills` value, never enumerate the private workspace or export provider/selection/binding values. A real SharedPreferences backup/restore regression verifies preservation, no workspace mutation and explicit-empty precedence.
- Sync conflict copies now retain source identity in the existing private sync preference store and re-export under that identity. **14 JVM tests** include repeated two-device round trips, a late joiner, controller restarts, distinct metadata/unrelated IDs/long IDs, failed provenance writes and interrupted imports. Existing copies without provenance—including fresh ZIP restores—keep their independent IDs; they can acquire one extra copy when first meeting the original. No existing user definitions are deleted. Registry/provenance writes are separate commits; allocation changes racing a failed final provenance write can still leave an extra copy. Actual file/paste import has consequently been routed through the same sync lease as WiFi confirmation so normal local imports cannot race an active sync.

Screenshot review found the AI panel inherited the reader paper palette, leaving it light even when the App was dark. Its theme boundary now captures App colors before the independent reader override and applies those colors to AI and nested settings. The book's skin remains independent. This final theme/gating change requires the next fresh build and screen checks below.

Further host-redirection attempts confirmed the production listener binds emulator Wi-Fi **10.0.2.16**; loopback and emulator Ethernet redirection did not reach that interface. Those failed transport setups are retained in `/tmp/inkflow-specified-book-host-18-api34.log` and `...-host-fresh-18-api34.log`. No physical Wi-Fi pass is claimed, and no emulator redirection or private validation source remains.


## Production integration21 / test-harness23 (superseded by integration24)

`/tmp/inkflow-integration-21.log`: BUILD SUCCESSFUL in **31 seconds**, core **225/225**, standard **200/200**, fullOcr **200/200**, all zero failures/errors/skips. Both production APKs and both test APKs built, strict lint **140 findings per flavor, 0 errors and 0 MissingTranslation**. Resource checker: **808 English base resources, 0 missing/extra/duplicate/format mismatches** in Chinese/Japanese/Korean. No translation exemptions. Integration20 was an intermediate successful build before the final independent AI/App theme boundary; integration21 was superseded by the compact layout repair in integration24.

Final theme/persistence targeted runs passed **11/11 each**: API31 compact/dark/1.3 in **36.870 seconds**, API34 wide/light/1.0 in **44.942 seconds**. This includes an additional actual Android SharedPreferences regression across two devices plus a late joiner, ten sync rounds and fresh controller instances; definitions and Agent choice remain stable and model usage is not introduced. Logs: `/tmp/inkflow-compact-21-api31.log`, `/tmp/inkflow-wide-21-api34.log`.

The API31 complete final production suite passed **262**, assumption-skipped **21**, failed **0** (**283 total**, 270.655 seconds, `/tmp/inkflow-full-21-api31.log`). Its skipped hardware/live-provider/opt-in cases remain uncovered, not passed.

Visual review confirms the final compact English AI panel uses App dark colors while reader paper remains light. One Japanese capture group was compositor-white despite ready accessibility semantics. Test-harness22 now rejects blank central viewports and waits for actual rendered content before saving a screenshot. Test-harness23 also refreshes/reacquires Home style dialog buttons until a click succeeds; successful clicks are never repeated. Both test APKs and both lint tasks rebuilt successfully in **11 seconds** (`/tmp/inkflow-ui-test-23.log`); production APKs are unchanged. Final guarded screenshot and fullOcr results follow below; earlier blank captures are not visual acceptance evidence.


## Production integration24: compact layout and final verification

The final production adjustment makes the Settings reading-engine controls flow onto a new row when needed. Visual inspection of a real 360dp / font-scale 1.3 Japanese capture found the second control squeezed into a vertical strip; accessibility-only success had not detected this. The FlowRow correction was rebuilt and all four compact language captures were inspected again: both engine choices are now readable, including Japanese and Korean. The App-dark / reader-paper separation remains visible.

`/tmp/inkflow-integration-24.log`: **BUILD SUCCESSFUL in 26 seconds**. Core JVM **225/225**, standard JVM **200/200**, fullOcr JVM **200/200**, zero errors/failures/skips. Both production APKs, both instrumentation APKs and strict lint built. Each lint report has **140 findings, 0 errors, 0 MissingTranslation**; warnings remain and no exemption was added. A fresh resource checker reports **808 base resources**, with zero missing/extra/duplicate/format mismatches in Chinese/Japanese/Korean.

The guarded integration23 compact run passed **5/5 in 31.396 seconds**; integration24 repeated it after the layout fix and passed **5/5 in 56.769 seconds** (`/tmp/inkflow-compact-book-24-api31.log`). Both explicitly enable the actual user EPUB test in addition to the four language methods. It uploads the supplied EPUB twice through the device-local HTTP server, checks byte hash and deduplication, real TOC/search/exact CFI, then opens the actual native reader and preserves its saved anchor without introducing AI usage. This proves the local server/import/reader path, **not host-to-phone physical WiFi**. All four screenshot sets now reject blank compositor frames before saving. The script restores the original display, density, font scale and night mode after testing.

The complete fullOcr integration21 API34 run ended with **2 failures** among 283 methods (Home style Save click and TXT vocabulary source flow); it is not a pass. `/tmp/inkflow-home-vocabulary-23-api34.log` passed the focused **8/8 in 26.215 seconds**, including both failing paths. Home actions reacquire and refresh current accessibility nodes; the vocabulary helper now refreshes nodes before use as well. Accepted clicks are never resent. Full suites on the final integration24 APKs are recorded below after they finish.


Final complete suites for production/test integration24:

| Variant / device | Passed | Assumption-skipped | Failed | Total | Runtime |
| --- | ---: | ---: | ---: | ---: | ---: |
| standard / API31 arm64, emulator-5582 | 262 | 21 | 0 | 283 | 264.709 s |
| fullOcr / API34 arm64, emulator-5580 | 266 | 17 | 0 | 283 | 335.425 s |

Logs: `/tmp/inkflow-full-24-api31.log`, `/tmp/inkflow-full-24-fullocr-api34.log`. Opt-in live-provider, physical speech, extended benchmark and real-book methods are not converted into passes by the runner's inclusive “OK (283 tests)” footer; exact skipped identities are retained in the verification manifest. The actual supplied book is covered separately by the explicit five-method compact invocation above.

The APKs correspond to native commit `dcc3606d`:

| Development APK | SHA-256 |
| --- | --- |
| [standard](app/build/outputs/apk/standard/debug/app-standard-debug.apk) | `24f198b3ea8aa4d14820525c0023107d2001310bf1171fa6365820b39aeac4b7` |
| [fullOcr](app/build/outputs/apk/fullOcr/debug/app-fullOcr-debug.apk) | `7cbb823f443e991ab910c0775bec02eac18b32248b454fafffb8d48f9fc72061` |

No physical device is currently authorized/connected. Host-to-device WiFi, current-build live full-book generation, two-hour continuous reading, physical TTS/lockscreen/Bluetooth, E-INK/foldable behavior, comparative performance and iOS parity remain open. The earlier performance sample covers the actual paged renderer, 20 opens and 100 hardware page actions on a debug emulator; it has no pre-change baseline and proves no percentage improvement. Existing Skill copies without provenance and a narrow concurrent-allocation plus preference-write-failure case can retain extra copies; no destructive deduplication is performed. This batch does not release all M0–M7 milestones.


Final wide language capture (`/tmp/inkflow-wide-24-api34.log`) passed **4/4 in 44.945 seconds** on the fullOcr integration24 APK at **960dp**, light mode, font scale 1.0. Render-guarded Library/Settings/Reader/AI/Statistics captures were saved for all four languages; Settings captures were visually checked. This complements the final **360dp**, dark, font-scale 1.3 set. It does not constitute the full device/UI matrix or mother-tongue translation review. Both AVD display overrides were restored after capture.
