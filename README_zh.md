# Inkflow

> A modern reader, elevated by AI. — 新时代原生 AI 辅助阅读器。

**Inkflow**（Android 包名：`com.aceinandroid.inkflow`）是一款以 **AI 深度辅助阅读** 为核心、基于 **Kotlin Multiplatform (`:core`) + Jetpack Compose** 原生打造的新一代电子书阅读器，并包含 iOS/iPadOS SwiftUI 原生起步工程（`ios-native/`）。

## 核心特性

- **AI 深度阅读工作台**：多模型供应商与 Keystore 加密存储、每书多会话持久化、10 种内置 Reading Skills、防剧透 Book Wiki 与上下文包、小说 Story Atlas（4 视图时间线 + 原生 Canvas 人物关系图）、AI 笔记整理审批与 30 天撤销日志、平板/横屏 `AI | 正文` 并排分栏。
- **分层原生阅读引擎**：
  - **主力原生 Canvas 分页引擎**：支持图文混排分页、滑动选句、跨段选区与端点微调、音量键整页翻页及独立 **E-INK 墨水屏高对比模式**；
  - **虚拟化滚动模式** + **直连 ZIP 流的原生轻量 WebView 兜底**（零本地 HTTP Server，兼容固定版式与复杂 CSS EPUB）；
  - 原生支持 **EPUB、PDF（缩放/目录/可选离线 OCR）、MOBI/AZW3、FB2、TXT（自定义分章规则）、Markdown**。
- **离线优先查词与翻译**：内置离线中文词典（`fullOcr` 变体）、MDX v2 离线词典导入、生词本复习与发音、显式 AI / DeepL / Microsoft / Google 翻译。
- **听书、同步与传书**：OpenAI / Azure 在线拟真朗读 + 系统 TTS 后台媒体服务、WebDAV 整库与自动增量同步（`inkflow/`）、自托管 HTTPS 进度同步、本地 ZIP 备份恢复及局域网 Wi-Fi 无线传书。
- **KMP 跨端共享核心 (`android/core`)**：分页、选区、AI 体系、证据校验、Story Atlas、同步增量与 TTS 队列全部沉淀于纯 Kotlin 核心，面向 Android 与未来 iOS/iPadOS 复用。

## 构建与测试

```bash
cd android
# 运行 KMP 核心单测与 Android 双变体单测
./gradlew :core:jvmTest :app:testStandardDebugUnitTest :app:testFullOcrDebugUnitTest

# 构建轻量版 (standard) 与全离线词典+OCR版 (fullOcr) APK
./gradlew :app:assembleStandardDebug :app:assembleFullOcrDebug
```
