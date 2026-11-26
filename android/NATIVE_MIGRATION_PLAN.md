# Inkflow原生端迁移实施与验收记录

更新：2026-11-26。应用：`com.aceinandroid.inkflow`，版本：0.2.0。

本文件将原 P0–P3 方案落到实际入口与验证范围。**CloudBase 同步从方案中移除**；
原生端只使用本地 ZIP、WebDAV 与自托管 HTTPS 进度同步。旧 Flutter 目录保留为
兼容性参考，不能视为新的原生依赖。

## P0：AI 工作台、阅读排版、词典翻译

- `core/ai` 注册请求、角色和供应商，统一上下文、10 种内置方法、自定义 Skill
  JSON、Token 统计与最多 4 次 Key 尝试。已输出内容后不重试，不将正文交给其他
  供应商。普通阅读、选区与面板事件不调用模型。
- Android 设置管理多个供应商、对话/轻量角色、Key 与显式连通测试。Key 经 Android
  Keystore 加密并排除 ZIP。每书多会话复用 v22 `tb_ai_sessions`，支持切换、重命名、
  删除和备份；兼容未知消息角色、字段与 SQL 列。
- 手机工作台默认 56% 高度，可展开；宽度至少 840dp 时右侧 400dp 分栏，阅读器保持
  同一组合位置。翻译携带源/目标语言进入轻量角色。历史与本书上下文使用冻结的
  章节、偏移及精确进度，回退阅读时隐藏后文。
- Canvas 分页容纳图片块，支持放大、明确选择滑动选句/翻页、端点微调、显示末尾、
  跨页延伸/撤回。微调到尚未显示的文字不能发送 AI。边距、段距、缩进、对齐、
  页眉页脚、时间电量可配置并持久化。
- 书籍详情支持书名/作者/封面编辑并保留进度。PDF 支持缩放平移和本地目录提取。
- 中文词典按分片查询；standard 支持文件导入，fullOcr 内置中文包。提供 MDX 子集
  导入、查词发音/释义与生词保存；翻译可显式调用 AI、DeepL、Microsoft、Google。
  第三方翻译 Key 仅限当前界面会话。

## P1：本书资料、任务、证据与可撤销写入

- `BookWikiProjector` 和上下文包复用既有 Wiki/Artifact 表；按同一阅读边界提供
  章节、人物、线索、概念与我的记录。来源可打开原文并返回原位置。
- 长任务统一持久化状态和 checkpoint；重启恢复为暂停，不能自动调用模型。只在
  用户确认已完成章节范围后分块请求；Evidence 必须能在允许的原文中确定性定位。
- 统一 `NativeReadingActions` 负责 AI 写入、前后快照和 30 天撤销；版本变化时拒绝
  覆盖后续修改。未知记录保留，不能借未知类型触发执行。
- 已有笔记可显式批量整理为待审批建议。任务冻结原笔记 ID/版本与来源快照，接受时
  更新同一笔记并产生工作区修订，拒绝不改正文，撤销拒绝覆盖后续用户编辑。
- Story Atlas 提供四种时间线视图、三档密度、人物过滤与章节折叠。人物径向图只绘制
  有原文证据的显式关系，支持平移缩放、简洁/完整模式与节点来源查看。结构解析
  保守识别出版信息、分卷与案件，不编造缺失的故事线或关系。
- 本地闭环策略与下一步建议无需模型；投影现有目标、难点、检查点和复习卡片，
  显式完成动作使用同一撤销契约。自动教练、新建检查点和卡片评分未迁移。
  详细边界见 [native-reading-p1.md](../docs/architecture/native-reading-p1.md)。

## P2：听书、同步、传书与包体

- 在线听书接入现有媒体会话和后台服务；按句切分，缓存容量两句，保存游标，
  使用官方 OpenAI 兼容语音与 Azure REST 接口。在线失败尝试系统 TTS。
- 进度、WebDAV 与本地库变更共用 single-flight 门控；自动同步在阅读中合流，
  阅读时长/位置完成保存后再调度。旧页面的迟到回调不能释放新页面的阅读门控。
- WebDAV 自动增量同步默认关闭，显式启用后在前台空闲、阅读保存后的安全时点运行。
  Reading Agent 兼容每书 MD5／每设备分支；词汇使用独立 v1 分支与 `und` 命名空间，
  删除产生 tombstone。逐包事务、用户纠正优先、保守剧透边界、未知 kind 原样保留，
  上传采用 ETag 条件写入；远端未知版本或无 ETag 时保留冲突。不会覆盖整库或合并
  书籍文件。单包上限 2 MiB／10,000 行，每轮下载 32 MiB；当前最多 128 本具备
  唯一 MD5 的本地书籍，每分支最多 64 台设备。超限会停止并报告，不会悄悄丢数据。
- 无线传书仅显式启动，绑定本地 Wi-Fi 私有地址，使用会话令牌，串行接收，关闭
  页面/应用转后台时停止。单文件与会话大小、数量均受限，文件复用原有导入器。
- 年度热力图、当天明细、当前/最长连续天数复用本地阅读时长。
- `standard` 不打包中文 OCR 模型及中文词典；`fullOcr` 打包两者。OkHttp 升级至
  4.12.0，APK 体积以实际构建结果为准，不承诺方案中的估算值。

## P3：iOS/iPadOS 起步

`:core` 通过 `-PenableIos=true` 显式启用 `iosArm64`、`iosSimulatorArm64` 与静态
`InkflowCore.xcframework`。Android 默认构建无需安装 Kotlin/Native。
`ios-native` 是独立 SwiftUI 原生起点，使用 TextKit 2 容器测量的 TXT 分页、PDFKit
与系统朗读。支持上一页／下一页、页码、旋转和字号变化后的阅读锚点保持，边界避免
拆开 Unicode cluster；选区转换回全文坐标。源代码
已通过 arm64 iOS Simulator Swift 编译/链接，测试源码也已类型检查；当前 Xcode 缺少
iOS 平台/模拟器运行时及匹配的 Native LLVM 依赖，尚未产出完整 XCFramework 或
启动模拟器，不能将配置注册和源码编译视为运行验收。11 个 XCTest 方法仅编译检查；
macOS TextKit 适配的两组 smoke 执行通过，不等于 UIKit 交互验收。详细命令与阻塞记录在
[iOS VALIDATION.md](../ios-native/VALIDATION.md)。完整
Android 业务 UI 与所有格式迁移不属于一个可宣称已等价的结果。

Android 的英文、中文、日文、韩文资源均覆盖全部 535 个字符串；XML 键集合、
重复项和格式占位符逐项比对通过。日／韩文复用旧版已有翻译并补齐原生新增入口，
不再使用局部英文回退或降低 `MissingTranslation` 严重级别。

## 构建与验证

```sh
cd android
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew \
  :core:jvmTest :app:testStandardDebugUnitTest \
  :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest \
  :app:assembleFullOcrDebug :app:assembleFullOcrDebugAndroidTest \
  :app:lintStandardDebug :app:lintFullOcrDebug
adb install -r app/build/outputs/apk/fullOcr/debug/app-fullOcr-debug.apk
adb install -r app/build/outputs/apk/androidTest/fullOcr/debug/app-fullOcr-debug-androidTest.apk
adb shell am instrument -w com.aceinandroid.inkflow.test/androidx.test.runner.AndroidJUnitRunner
```

本次验证记录在下方维护。付费模型/翻译/语音和真实 WebDAV 云服务与本地拦截器、
SQLite 及离线测试分开记录。测试仅清理自己的临时数据，不清空用户书库。

## 2026-11-26 集成验收记录

- 共享核心 JVM：104 项通过；Android JVM：64 项通过。
- 最终 APK 上日／韩语书架与设置页已完成模拟器 smoke：本地化资源加载正常，
  设置页可读且未见文字截断或崩溃。未对全部页面进行母语审校。
- 最终双变体主 APK、AndroidTest APK 构建通过。standard／fullOcr 严格 lint
  均为 0 错误、107 条警告、6 条提示，缺失翻译为 0；未添加 lint baseline 或整体
  降级配置。所有已跟踪改动和 128 个新增源码／配置／文档文件的空白检查通过。
- 已构建 standard／fullOcr 主 APK 与各自 AndroidTest APK。Debug APK 实测分别为
  32.25 MiB／90.08 MiB；standard 不含中文词典和 ML Kit，fullOcr 含 82 个中文词典
  JSON 分片及许可证、31 个 ML Kit 相关条目。两者均未打包 Flutter 运行时。
- Android 34 模拟器 fullOcr 全量共 143 项：136 项通过、7 项条件跳过、0 失败。
  跳过包括 5 项需要系统 TTS 引擎的用例、1 项依赖模拟器缺失字体的用例、1 项仅适用
  standard 无词典资源的用例。无 TTS 引擎时的受控行为另有通过测试。
  结果来自仪器测试逐项状态码，而非将 JUnit 的总数视为全部通过。
- standard 定向仪器回归共 12 项：10 项通过、2 项 fullOcr 专属用例条件跳过、0 失败。
  覆盖后台阅读位置、EPUB 恢复、音量翻页、自动增量同步、真实 Socket 传书导入和
  无内置中文词典时的明确提示。
- 后台进度保存改为取得最新正文快照，避免将中段 TXT 进度误写成 100%；EPUB
  跨章来源与返回读取明确偏移，提供实际后台场景及 CFI／offset 回归测试。
- 付费模型、翻译、在线语音未使用真实凭据调用；WebDAV 用条件写入传输及
  SQLite／双设备夹具验证，尚未连接真实外部服务。
- iOS 运行环境与 XCFramework 链接限制见 `ios-native/VALIDATION.md`。
