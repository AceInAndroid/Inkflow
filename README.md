# Inkflow

> A modern reader, elevated by AI.

**Inkflow** (`com.aceinandroid.inkflow`) is a native, local-first AI-assisted e-book reader built with **Kotlin Multiplatform (`:core`)** and **Jetpack Compose** on Android, with an iOS/iPadOS SwiftUI native starter (`ios-native/`).

## Highlights

- **AI-First Reading Workspace**: Multi-provider AI gateway (OpenAI-compatible, DeepSeek, Anthropic Claude, Gemini), Android Keystore-encrypted API keys, persistent per-book multi-session history, 10 built-in Reading Skills, spoiler-safe Book Wiki & Context Packs, Story Atlas (4-view timeline & native Canvas character graph), AI note organization with 30-day undo, and wide-screen split view (≥840dp).
- **Layered Native Reader Engine**:
  - **Native Paged Canvas Engine**: True horizontal pagination with mixed text/image layout, swipe-to-select-sentence, cross-paragraph selection with fine endpoint controls, hardware/volume-key paging, and dedicated **E-INK high-contrast mode**.
  - **Virtualized Scroll Mode** & **Direct-ZIP Native WebView Fallback** for fixed-layout or complex CSS EPUBs (zero local HTTP server).
  - Supports **EPUB, PDF (with zoom, outline & optional on-device Chinese/Latin OCR), MOBI/AZW3 (`libmobi`), FB2, TXT (custom chapter rules), and Markdown**.
- **Offline-First Dictionary & Translation**: Built-in Chinese dictionary shards (`fullOcr` flavor), MDX v2 offline dictionary import, vocabulary review with pronunciation, and explicit AI/DeepL/Microsoft/Google translation.
- **Sync, Audio & Transfer**: Online AI TTS (OpenAI/Azure) + system TTS media service, manual & automatic incremental WebDAV sync (`inkflow/`), self-hosted HTTPS progress sync, local ZIP backup/restore, and foreground Wi-Fi wireless book transfer.
- **KMP Shared Core (`android/core`)**: Pure Kotlin pagination, selection, AI prompts/skills/gateway, evidence resolver, Story Atlas projector, sync delta, and TTS queue shared across Android and future iOS/iPadOS.

## Build & Test

```bash
cd android
# Unit tests (KMP core + Android)
./gradlew :core:jvmTest :app:testStandardDebugUnitTest :app:testFullOcrDebugUnitTest

# Build lightweight standard APK or full offline OCR + Chinese dictionary APK
./gradlew :app:assembleStandardDebug :app:assembleFullOcrDebug
```
