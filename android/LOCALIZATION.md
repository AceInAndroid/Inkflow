# Inkflow Android localization

后续生成任务、向前跨章修复及持续阅读验证见 [CONTINUED_TEST_2026-11-26.md](CONTINUED_TEST_2026-11-26.md)。

This document describes the implementation, reproducible validation commands, and the results observed on November 26, 2026. The final section distinguishes completed checks from remaining coverage gaps.

## Language options and resources

Settings → App language offers Automatic plus four manual options:

| Option | Persisted choice | Resource directory |
| --- | --- | --- |
| Automatic / follow system | `auto` | Selected by the resolver |
| English | `en` | `app/src/main/res/values/` |
| 简体中文 | `zh-Hans` | `app/src/main/res/values-zh/` |
| 日本語 | `ja` | `app/src/main/res/values-ja/` |
| 한국어 | `ko` | `app/src/main/res/values-ko/` |

[AppLanguage.kt](core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/localization/AppLanguage.kt) defines tags and native names. [LanguageSettingsRow.kt](app/src/main/kotlin/com/aceinandroid/inkflow/localization/LanguageSettingsRow.kt) renders the picker and effective automatic-language summary. [locales_config.xml](app/src/main/res/xml/locales_config.xml) declares the four manual languages to Android. Automatic is an application policy, not another locale tag. There is one Chinese UI: Chinese script and region variants resolve to Simplified Chinese.

## Automatic resolution

[AppLanguageResolver.kt](core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/localization/AppLanguageResolver.kt) is the authoritative mapping, including complete explicit time-zone sets and historical aliases. It is a pure core resolver with this priority:

1. A supported manual selection wins over every environmental signal.
2. A recognized region selects its language.
3. A recognized named time zone selects its language.
4. The first supported language in the original ordered system-locale list wins.
5. Otherwise, use English.

`LanguageResolutionSource` records `MANUAL`, `REGION`, `TIME_ZONE`, `SYSTEM_LANGUAGE`, or `FALLBACK`, including when several inputs would produce the same language.

The complete region mapping is small:

| Region codes | Language |
| --- | --- |
| `CN`, `TW`, `HK`, `MO` | Simplified Chinese |
| `JP` | Japanese |
| `KR` | Korean |
| `US`, `GB`, `IE`, `AU`, `NZ`, `CA` | English |

Region codes are trimmed and compared without case sensitivity. An unsupported region continues to the zone step; it does not immediately select English.

Time-zone matching uses explicit identifiers, trimmed and lowercased. Examples include `Asia/Shanghai`, `Asia/Urumqi`, `Asia/Taipei`, `Asia/Hong_Kong`, `Asia/Macau`, and aliases such as `PRC`, `ROC`, and `Hongkong` for Chinese; `Asia/Tokyo` / `Japan` for Japanese; and `Asia/Seoul` / `ROK` for Korean. The English set covers listed identifiers for the United States, United Kingdom, Ireland, Australia, New Zealand, and Canada, including historical aliases such as `US/Pacific`, `GB-Eire`, `NZ-CHAT`, and `Canada/Eastern`. See `chineseZones`, `englishZones`, and `zoneLanguage` in the core file for the full membership rather than duplicating those lists here.

Offsets and prefixes do not establish a region: `UTC`, `GMT`, offset-like identifiers, an arbitrary `America/…` identifier, or an unlisted geographic zone do not select a language merely because of their shape or current offset. Unknown zones fall through to the system-language list. This also avoids confusing places that currently share an offset.

System-language tags are trimmed, underscores are normalized to hyphens, and the base language is matched case-insensitively against `en`, `zh`, `ja`, and `ko`. Unsupported entries are skipped while preserving order. For example, an unrecognized region and zone with system tags `[fr-FR, ko-KR, ja-JP]` select Korean. A recognized `JP` region wins even if the zone is `America/New_York` and the system language is English.

## Raw system environment and refresh

[NativeLocaleController.kt](app/src/main/kotlin/com/aceinandroid/inkflow/localization/NativeLocaleController.kt) assembles `LanguageEnvironment` from the operating system:

- API 33 and later: `LocaleManager.systemLocales` supplies the ordered raw system list.
- Earlier Android versions: `Resources.getSystem().configuration.locales` supplies that list.
- The first system locale supplies the country. A two-letter Unicode `rg` override ending in `zzzz` takes precedence, for example `en-u-rg-jpzzzz` supplies `JP`.
- `TimeZone.getDefault().id` supplies the named zone.

Automatic mode must not read the app's overridden resource locale as the system signal; that would feed a previous manual selection back into automatic resolution. System locales and per-app `applicationLocales` have separate purposes.

[NativeLocaleApplication.kt](app/src/main/kotlin/com/aceinandroid/inkflow/localization/NativeLocaleApplication.kt) initializes the controller and refreshes it for locale/time-zone broadcasts and configuration changes. [MainActivity.kt](app/src/main/kotlin/com/aceinandroid/inkflow/MainActivity.kt) also refreshes on resume and configuration changes. Controller operations run on the main thread.

## Android per-app locales and initialization migration

On API 33 and later, manual choices are mirrored into `LocaleManager.applicationLocales`. Automatic mode keeps that platform list **empty**, preserving Android's follow-system state even when the application resolver chooses a language from region or zone. The localized context supplies the effective UI language independently.

The device-local preference file `native_app_language` contains:

| Key | Meaning |
| --- | --- |
| `choice` | `auto` or a supported manual language tag |
| `last_applied_tag` | Last platform choice written by the controller: empty in Automatic, manual tag otherwise |
| `platform_initialized` | Whether the API 33+ platform reconciliation has been initialized |

The initialization marker distinguishes migration from a later external reset:

- At first platform initialization, an empty platform list preserves an existing local manual choice, such as one saved before an Android upgrade. The controller then writes that manual tag and records initialization.
- At first initialization with a nonempty platform selection, the supported platform language becomes the manual choice.
- After initialization, a platform selection that differs from `last_applied_tag` is treated as an external edit. A supported tag selects manual mode; an empty or unsupported selection selects Automatic. Choosing the currently resolved automatic language explicitly in Android still means manual mode.
- A direct in-app selection saves `choice` and refreshes with platform reconciliation disabled for that operation, so the previous platform value cannot overwrite the new choice.

The controller commits the expected platform tag and initialization marker before notifying Android. This prevents its own configuration callback from being mistaken for an external edit. On pre-33 Android, the local choice and resource wrapper provide selection without `LocaleManager` application locales.

## Stable contexts and state preservation

`NativeLocaleContext` in [NativeLocaleController.kt](app/src/main/kotlin/com/aceinandroid/inkflow/localization/NativeLocaleController.kt) keeps its object identity and returns resources for the current effective locale. Its cached resources are replaced when the derived configuration changes; the context itself is not replaced. `applicationContext()` likewise returns the stable localized application wrapper.

[LocalizedApp.kt](app/src/main/kotlin/com/aceinandroid/inkflow/localization/LocalizedApp.kt) remembers the activity wrapper and supplies localized `LocalContext`, `LocalConfiguration`, and explicit `LocalResources` values to Compose, including already-open dialogs and sheets. All supported languages use left-to-right layout. The [manifest](app/src/main/AndroidManifest.xml) handles `locale|layoutDirection` configuration changes in `MainActivity`; language selection does not call `recreate()`.

MainActivity attaches a localized base context and reads its current resources directly so Android does not retain stale themed resources. Component callback registration forwards to the original Application; this avoids recursive application-context forwarding on older Android.

This design keeps remembered stores, selected pages, reader state, and in-flight work attached while labels recompose. Instrumentation verifies the Settings page stays open and that a native paged reader retains its chapter, exact offset, progress, and visible page and still accepts page turns. Explicit activity recreation is a separate lifecycle case; the settings test exercises it independently from ordinary language selection.

## Device-local persistence and portable data

Language choice belongs to the device. [NativeSettingsBackup.kt](app/src/main/kotlin/com/aceinandroid/inkflow/NativeSettingsBackup.kt) uses an explicit allowlist for `native/settings.json`; `native_app_language` is absent from that allowlist. Capture and restore therefore exclude its choice, reconciliation tag, and initialization marker.

[NativeBackupManager.kt](app/src/main/kotlin/com/aceinandroid/inkflow/NativeBackupManager.kt) uses that settings serializer for ZIP export and restore. A portable library restore does not transfer another device's app-language preference. The manifest also disables Android application backup with `allowBackup="false"`.

[NativeWebDavSync.kt](app/src/main/kotlin/com/aceinandroid/inkflow/NativeWebDavSync.kt) uploads the library database and referenced book/cover files from its staged export. It does not publish the language preference file. Download restores the staged library with `preserveLocalPreferences = true`. Language preferences also live outside the databases and reading-position records used for synchronization. Keep these boundaries when extending portable settings or sync packages.

## Stable skills and presentation formatting

[LocalizedDisplay.kt](app/src/main/kotlin/com/aceinandroid/inkflow/ui/localization/LocalizedDisplay.kt) projects built-in skill titles and summaries to resource strings using stable IDs such as `reading.general`, `reading.socratic`, and `reading.characters`. Those IDs, persisted session references, and model instructions do not change with the UI language. Custom or unknown skill IDs fall back to their own title and summary. Localization does not translate user-authored content, book text, or custom provider names. Provider, task, translation-language, and undo labels are also display projections rather than changes to stored identifiers or status values.

Date/time formatting uses the effective app resource locale at presentation boundaries:

- [ReadingStatisticsScreen.kt](app/src/main/kotlin/com/aceinandroid/inkflow/ReadingStatisticsScreen.kt) formats count values with `NumberFormat`, weekday names with `DateFormatSymbols`, and date labels with locale-specific patterns from `DateFormat.getBestDateTimePattern`. ISO-style date keys remain unchanged for storage, grouping, and sync; malformed display keys fall back to their original text. Durations use localized resource templates.
- [ReaderRunningStatus.kt](app/src/main/kotlin/com/aceinandroid/inkflow/ui/reader/ReaderRunningStatus.kt) derives the clock pattern from the app locale and honors Android's 12/24-hour setting. Locale is a producer key, so a change restarts the displayed clock computation.
- [LibraryNotesScreen.kt](app/src/main/kotlin/com/aceinandroid/inkflow/LibraryNotesScreen.kt) formats revision timestamps with localized medium dates and the device's 12/24-hour short time; [NativeVocabularyScreen.kt](app/src/main/kotlin/com/aceinandroid/inkflow/NativeVocabularyScreen.kt) formats review dates with the app locale.

These are display changes. They do not rewrite timestamps, date keys, skill IDs, or other portable data, and they do not change the device time zone.

## Reproducing validation

The following commands reproduce the relevant checks; observed results are recorded below. Use JDK 17 and the project's configured Android SDK/NDK. Run commands from `android/` unless stated otherwise.

The pure resolver suite is [AppLanguageResolverTest.kt](core/src/commonTest/kotlin/com/aceinandroid/inkflow/core/localization/AppLanguageResolverTest.kt):

```sh
./gradlew :core:jvmTest \
  --tests 'com.aceinandroid.inkflow.core.localization.AppLanguageResolverTest'
```

Build the standard debug app and instrumentation APKs:

```sh
./gradlew :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest
```

Use an explicit device serial. Repeat device validation on a phone and a wide emulator. A width of at least 720 dp exercises the home navigation rail; at least 840 dp exercises the docked AI workspace. Record API level, logical width, system locales, named time zone, and initial app-language mode. Include API 33+ for platform reconciliation and, when available, a pre-33 configuration for the resource-only path.

```sh
export ANDROID_SERIAL='<device-serial>'
export RESULTS="$PWD/localization-results/$ANDROID_SERIAL"
mkdir -p "$RESULTS"
adb -s "$ANDROID_SERIAL" shell getprop ro.build.version.sdk
adb -s "$ANDROID_SERIAL" shell wm size
adb -s "$ANDROID_SERIAL" shell wm density
adb -s "$ANDROID_SERIAL" shell getprop persist.sys.timezone
adb -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/standard/debug/app-standard-debug.apk
adb -s "$ANDROID_SERIAL" install -r -t app/build/outputs/apk/androidTest/standard/debug/app-standard-debug-androidTest.apk
```

Run classes separately so each result can be attributed to its own scope:

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.aceinandroid.inkflow.NativeAppLanguageTest \
  com.aceinandroid.inkflow.test/androidx.test.runner.AndroidJUnitRunner \
  > "$RESULTS/app-language.txt" 2>&1

adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.aceinandroid.inkflow.NativeLocalizedNotificationTest \
  com.aceinandroid.inkflow.test/androidx.test.runner.AndroidJUnitRunner \
  > "$RESULTS/notification.txt" 2>&1

adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class com.aceinandroid.inkflow.NativeLocalizedScreensTest \
  com.aceinandroid.inkflow.test/androidx.test.runner.AndroidJUnitRunner \
  > "$RESULTS/screens.txt" 2>&1
```

Inspect the instrumentation summaries, failure traces, and assumption skips in all three logs; a shell command's exit status alone is not evidence that the tests completed successfully. `NativeAppLanguageTest` includes controller/resource refresh, Settings navigation, explicit recreation, external application-locale changes, first-initialization migration, mid-chapter state preservation, and portable-backup exclusion checks. Its API 33-specific methods are conditional. `NativeLocalizedNotificationTest` constructs media notifications to check localized actions while keeping book titles intact; it does not require narration playback.

`NativeLocalizedScreensTest` exposes `englishLocalizedScreens`, `simplifiedChineseLocalizedScreens`, `japaneseLocalizedScreens`, and `koreanLocalizedScreens`. Use `-e class 'com.aceinandroid.inkflow.NativeLocalizedScreensTest#japaneseLocalizedScreens'`, for example, to select one method. Each locale method is intended to capture library, settings, statistics, reader, and AI-sheet pages through native accessibility, with a uniquely imported TXT fixture and bounded waits. It opens and closes the AI panel without submitting a prompt or selecting a quick action; actual model calls are outside this acceptance run.

Screenshots are written under the target app's `getExternalFilesDir(null)`:

```text
language-acceptance/<en|zh-Hans|ja|ko>/<page>-<width>x<height>.png
```

The class produces 20 screenshots per device configuration. Page names are `library`, `settings`, `statistics`, `reader`, and `ai-sheet`; dimensions in filenames are pixels. On a typical primary-user device, collect them with:

```sh
adb -s "$ANDROID_SERIAL" pull \
  /sdcard/Android/data/com.aceinandroid.inkflow/files/language-acceptance \
  "$RESULTS/"
```

Use the actual target external-files path if the device's storage/user layout differs. Review images for clipped labels, overlaps, wrong-language fallbacks, phone sheet layout, and the wide docked panel. Accessibility assertions alone cannot establish visual acceptance. The test restores its previous language/reader settings and removes its fixture; screenshots remain available for collection.

## Observed validation — November 26, 2026

The tested Inkflow revision extends base commit `4cfc0f86`; the language implementation and this acceptance record are delivered together in the following feature commit. Validation used Gradle 8.14 and JDK 17.0.14 (JetBrains Runtime), Android SDK at `/Users/bingzhang/Library/Android/sdk`. Evidence is retained in the ignored `build/outputs/localization-validation/` directory. The final integrated command was:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew \
  :core:jvmTest :app:testStandardDebugUnitTest \
  :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest \
  :app:assembleFullOcrDebug :app:assembleFullOcrDebugAndroidTest \
  :app:lintStandardDebug :app:lintFullOcrDebug --console=plain
```

**Result: BUILD SUCCESSFUL**, 168 tasks, 56 seconds. This run's log is `build/outputs/localization-validation/build.log`.

| Check | Observed result |
| --- | --- |
| Shared core JVM | 120 passed, 0 failures/skips, including 16 resolver tests; XML reports in `core/build/test-results/jvmTest/` |
| Android standard JVM | 64 passed, 0 failures/skips; XML reports in `app/build/test-results/testStandardDebugUnitTest/` |
| APKs | standard/fullOcr app and AndroidTest debug APKs built successfully |
| Strict lint, both variants | Each: 0 errors, 111 warnings, 6 hints; MissingTranslation 0. No new translation exemptions, suppressions, or lint baseline |
| Resource consistency | 605 keys in each of en/zh/ja/ko; identical keys and format placeholders, no duplicates |
| Android 12 / API 31 standard | AOSP ARM64, `emulator-5556`, 720×1280, 320 dpi, 360 dp wide / 568 dp usable height; en-US for four-language UI tests. 18 tests: 16 passed, 2 API-33-only assumption skips, 0 failures |
| Android 14 / API 34 fullOcr | AOSP ARM64, `emulator-5554`, 2560×1600, 240 dpi, about 1707 dp wide; raw fr-FR, Asia/Shanghai. 12 language/notification/screen tests passed, no skips |
| Real automatic timezone changes during reading | Separate opt-in test passed on both API 31 and API 34, with unsupported fr-FR region: Tokyo → Seoul → Tokyo, automatic mode retained, chapter/exact offset/progress preserved and paging remained functional |
| API 31 cold start and reinstall | Actual in-app Korean selection survived two force-stop/restart cycles and `adb install -r`; actual picker restored Automatic / English for en-US |
| API 34 cold start / platform reconciliation | External Korean application-locale setting survived force-stop/restart; clearing Android application locales restored Automatic. Instrumentation also covers first platform initialization migration and explicitly selecting the currently automatic language |
| UI / visual checks | 20 final phone screenshots plus 20 final wide screenshots, four languages × library/settings/statistics/reader/AI panel; reviewed for clipping and overlap. Japanese/Korean controls fit; short-screen AI sheet height was corrected so the composer stays visible |
| Notifications | Real service notification construction checked in all four languages on both API levels; translated actions and original book title verified |
| No model requests | Language tests configure a loopback model endpoint and assert zero accepted connections during ordinary reading and language changes |
| Local preference / portable backup | Settings capture excludes `native_app_language`; ZIP/WebDAV call sites and allowlist reviewed. No backup schema or database changes |

The 18 API 31 tests include the three new language test classes plus existing reader lifecycle and volume-key regressions. The 12 API 34 tests cover the three new language classes. The two additional automatic-timezone tests are separate runs, not included in those totals. The earlier migration acceptance results in NATIVE_MIGRATION_PLAN.md describe previous work and are not a claim that the full historic suite was rerun here.

Relevant evidence files under `build/outputs/localization-validation/`:

- `api31-tests.log`, `api34-fullocr-tests.log`: final instrumentation status codes and summaries.
- `api31-automatic-reader.log`, `api34-automatic-reader.log`: actual automatic timezone-switch regression.
- `api31-cold-start.log`, `api34-cold-start.log` and corresponding `.py` harnesses: process-death, in-app picker/platform, and reinstall checks.
- `api31-phone/language-acceptance/`: 20 screenshots ending in `-720x1280.png`.
- `api34-wide/language-acceptance/`: 20 final wide screenshots ending in `-2560x1600.png`; older phone-size captures in that directory are not wide acceptance evidence.
- `phone-contact.png`, `wide-contact.png`: contact sheets used for visual review.

To reproduce the opt-in automatic reading test, use only a disposable emulator with raw system locale fr-FR (unsupported region) and enable the test argument below. The test changes and restores the emulator's timezone. Do not run it on a user's physical device.

```sh
adb -s "$ANDROID_SERIAL" shell am instrument -w -r \
  -e class 'com.aceinandroid.inkflow.NativeAppLanguageTest#switchingLanguageMidChapterPreservesReaderAndStillAllowsPaging' \
  -e exerciseTimeZoneChanges true \
  com.aceinandroid.inkflow.test/androidx.test.runner.AndroidJUnitRunner
```

### Deliverables

The hashes below identify the earlier localization acceptance build. Subsequent LAN model and streaming-session fixes, current APK hashes, and live book validation are recorded in [LIVE_TEST_2026-11-26.md](LIVE_TEST_2026-11-26.md).

| APK | Size | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/standard/debug/app-standard-debug.apk` | 33,364,084 bytes / 31.82 MiB | `a607bcf7660218376bc7bc01ac46d0bb07c6e674d73141c57ca932da506d41de` |
| `app/build/outputs/apk/fullOcr/debug/app-fullOcr-debug.apk` | 94,731,305 bytes / 90.34 MiB | `3c19b1d330c997a62adf65ebef4850a2f0d1245e80dd41b95ad1e14a3a4c2205` |

Both APKs contain all four language resources. AAB language splitting is disabled in the build configuration; an AAB was not built in this validation round.

### Coverage limits

- API 31 and 34 cover pre-33 and 33+ behavior; exact API 24/32/33 versions and OEM-specific application-language settings pages were not exercised. Platform changes used the real LocaleManager API and Android shell, not a manual tour of an OEM Settings app.
- The pre-33 preference migration was simulated on API 34; no physical Android OS upgrade was performed.
- This round ran the focused language/lifecycle suite, not the entire historic 143-test native instrumentation suite.
- Four-language visual checks do not replace a native-speaker linguistic audit. Compact English bottom navigation still uses the existing single-line ellipsis behavior; Japanese/Korean labels fit in the checked small-screen configuration.
- Media notification localization was verified without a speech engine; actual TTS playback and voice switching were not exercised.
- No production model, online translation, speech, or external WebDAV service was invoked. Backup exclusion was verified through capture/allowlist and call-site review, not a full remote WebDAV transfer.
