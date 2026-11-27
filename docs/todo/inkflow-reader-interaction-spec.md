# Inkflow 阅读交互规范

当前构建、失败修复与设备覆盖见[2026-11-27 实施验收记录](../../android/IMPLEMENTATION_2026-11-27.md)。分项通过不能替代整阶段放行。

更新：2026-11-27。状态：批准的产品规格，已进入实现；R1/R2、双页及 TTS 已新增代码，按接入完整性记为已实现待验收或部分实现。gesture/edge core 状态机已实现，renderer 已接入单一 pointer 仲裁、手柄、放大镜及可选边缘扩选，实际拖动/边缘行为仍待设备验收；完整录屏交互与多引擎一致性尚未验收。

关联：[总体路线](inkflow-master-roadmap.md)、[UI 规格](inkflow-ui-structure-and-specs.md)、[AI 与 Skill](inkflow-ai-interaction-and-skills-spec.md)、[R1–R8 引擎](inkflow-reader-engine-v2.md)、[选区既有契约](../design/selection/DESIGN.md)。R1–R8 编号和核心职责保持不变；本文细化 R2/R3/R4/R5 的交互边界。

## 阅读现场与坐标权威

正文位置使用书籍、章节 href、CFI/字符 offset 和 source revision，不能用像素/页码作为跨布局的唯一定位。Canvas 使用当前布局 UTF-16 offset，WebView 使用可验证 DOM Range/CFI 并通过已有适配器互转；PDF 固定页以页号及原生文本能力降级，不伪造字符选区。

选区会话记录书/章/来源、起止 offset、session/revision、当前可见末尾与跨页能力。只有当前会话/版本可改端点；迟到分词、异步跨页、旧语言/布局回调不得覆盖新选区。长按/扩选/打开菜单本身不调用模型。

改字号、旋转、双页⇄单页、AI 调宽按可见字符锚恢复；保留 reader 实例、选区和草稿。无法映射来源时解释并保留可恢复文本，不跳到未知位置。

## 三层候选者轮询与触摸仲裁（R5）

[ReaderGestureArbiter](../../android/core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/ReaderGestureArbiter.kt) 已实现共享语义状态机，平台接入须复用现有事件入口逐步收敛；不能与 renderer 并行争抢同一 pointer。API 与接入职责见[core 集成说明](../architecture/reader-gesture-core.md)。三层是候选分组，层内按下列顺序轮询，保持原 R5 六级优先级：

| 候选层 | 命中优先级 | 独占与取消 |
| --- | --- | --- |
| 1 现场控制 | 选区手柄拖动、当前选区命令/明确取消 | 手柄拖动一旦 claim，TTS/链接/翻页全部 cancel；取消选区的点击不透传翻页 |
| 2 内容目标 | 当前 TTS 高亮句点击 → 页内链接/脚注/图片 → 已有划线批注 | 只有确切点击才消费；手势超出 slop 时放弃未 claim 候选，不能误播/误开链接 |
| 3 页面动作 | 默认滑动选句（可显式设滑动翻页）→ 边缘点击翻页 → 中央点击 Chrome | 依据用户偏好和 renderer 能力选单一路径；无可用动作则无操作 |

down 时建立快照和候选列表，move/up 轮询可接受的候选；一旦 claim，其余收到 cancel，直到 up/cancel 结束，不能转交一半手势。系统中断、跨布局或进入弹层清理 pointer 状态；已完成动作不重复。点击 TTS 与链接重叠时以表中顺序为准，长按仍能进入选区，不能一按便暂停。

必须覆盖手柄覆盖链接/朗读句、长按后移动、现有划线与正文选择、脚注/图片、返回/系统取消、音量/键盘翻页及双页点击边界。E-INK 使用相同语义，无连续动画要求。

## 选区、显式跨页与圆形放大镜

长按定位词/句，水滴手柄调端点；普通屏拖动时显示 **圆形、1:1 现场放大镜**，采样当前正文位置，避免旧帧/邻页伪影，避开手指及屏幕 inset，松手关闭。放大镜不采样菜单、凭据或其他页面，且不是整屏截图服务。系统不支持/内存不足时保留手柄与文本预览；E-INK 完全禁用。

**默认扩选是显式操作**：主/更多工具里提供“向前扩展一页”“向后扩展一页”“撤回扩展”“查看选区末尾”等等价命令；显示当前范围及是否已可验证。键盘与 E-INK 均可不拖拽完成。只在同章来源可准确定位时扩展；跨章尚不支持时说明，不能串接不连续文本。

拖边跨页仅可选增强，设置明确标“边缘停留扩选”，默认关闭，E-INK 强制关闭。普通屏开启后，手柄在边缘停留目标 **600ms** 首次扩页，连续目标 **900ms**，最多相对起始 ±4 页；离边/松手/取消/隐藏/切章/布局改变立即取消计时。不能与显式命令重复扩选、越过未加载来源或触发双重翻页。[ReaderEdgeSelectionController](../../android/core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/ReaderEdgeSelectionController.kt) 已实现以上计时、页距与 revision/cancel 保护；阈值不是设备验收结论，实际手柄/生命周期/翻页接入与设备校准仍待完成。

选区可能跨过尚未显示的末尾。模型发送前需验证终点边界；不能验证时先给“显示末尾”显式本地导航，再允许发送。边缘扩选不隐式赋予全书生成授权。

## 双层深色胶囊与三样式五色板（R2）

主菜单使用深色磨砂圆角胶囊，围绕选区或安全区域放置，避开手柄/键盘/屏幕边缘；E-INK 用纯色高对比。主动作按语义稳定：词典候选优先查词/词汇/AI/更多；普通片段优先翻译/AI/复制/更多；划线入口清楚可见。更多包含网络搜索、整段翻译、听选区、笔记与分享，按能力显示，不留死控件。

点“划线”**保留选区和主胶囊**，主栏上方出现二级迷你胶囊：三种 `A` 样式按钮（荧光、直线、波浪线）+ **五个颜色圆点**。样式与颜色切换本地即时预览、可见选中态/文字标签，不能只靠色盲难辨颜色。空间不足将副栏移到可见位置，不压正文手柄。E-INK 保留样式形状/文字选择，用静态底色/线型区分。

默认五色为黄色、绿色、蓝色、粉色、紫色，具体色值按阅读皮肤保证可读；夜读用可见变体。更丰富虚线为直线样式可选扩展，不能增加第四个必选主样式。最后样式偏好可本地记住，不覆盖已存在划线。

样式预览与持久写入分开：选择样式/颜色构成明确修改动作，连续交互可以先本地预览、结束时合并为一次受验证事务/撤销记录。提交前验证来源和 annotation revision；失败保留可操作选区及旧批注，不显示假成功。复制/分享/转 AI 只在命令被接受后按其语义消费选区。

已有划线点击弹出编辑/改色/笔记/删除栏。划线删除不删除独立用户笔记。所有持久变更复用现有 annotation/action 服务、版本冲突和 30 天撤销；后续编辑造成冲突必须拒绝旧撤销，不覆盖新修改。

## 五层绘制、合并与性能（R1/R2）

顺序固定：Background → Underlay 用户划线 → Content 正文/插图/表格块 → Overlay 搜索/TTS/AI_SOURCE/AUTO_READ 与页眉页脚 → Interactive 手柄/放大镜。

用户批注持久化为来源区间，与分页解耦。每页按 LineMetrics 合并同批注连续区间，裁切到本页行；同一来源且相同样式/颜色的邻接批注才可按已有合并策略归一，异样式或含不同独立笔记不可丢失。绘制不按每字符创建对象，瞬时高亮不持久化为批注。

页边界缓存指纹包括引擎版本、书/章 hash、字体/排版、实际单页 viewport、单双页布局模式；失效缓存可重排，不损坏用户数据。双页只取当前左右页和有限邻页窗口，不把全书位图常驻。大章 ≥10 万字、改字号/旋转、上一章末页与 E-INK 静态路径均是后续验证门禁。不能将“0ms 秒开”设计愿景写成实测保证。

## 听书与听读双向同步（R3）

播放器规范见[UI 听书视图](inkflow-ui-structure-and-specs.md)。系统/在线 TTS 复用已有队列、音频焦点、通知与设置；无引擎说明后可安装/改配置，不持续重试。真实 TTS 需要独立播放证据，缺引擎测试跳过不算通过。

- 每个句块带 chapter、来源 hash、`startOffset/endOffset`；不同 API 的范围统一映射到全章 UTF-16。正文用蓝色高亮当前句；颜色不足时加静态线/边框。
- 句跨页使用 `pendingTurnCharPos`，句终点超出本页时定位包含终点或下一半的页；单句跨两页时记录下一页起点，播完/达到约定进度后只翻一次。新朗读块、seek、手动翻页、切章和停止均清理旧 pending，迟到回调不能拉回旧位置。
- 双页按两个可见页范围判断是否需要翻组；AI 协作按左侧单页判断。翻页不丢朗读队列或重复播句；E-INK 按句/页刷新，不逐字闪烁。
- 点当前高亮句暂停/续播；选区菜单“从这里听”从起点起播；相对 seek 15 秒/章节跳转以实际引擎能力处理，不能以页数近似秒数。
- 阅读位置与播放位置不同显示“当前阅读与播放位置不一致 · 听当前”。点击后才把播放移到当前正文；从播放器点“原文/到播放处”才把正文移到播放源。自动跟读仅按用户显式启用状态进行，用户自由翻看时不抢位置。
- 语速/音色“仅应用当前书”与全局默认分开；通知/迷你条/全屏播放器同一状态源。后台/来电/耳机拔出按音频焦点规则暂停，恢复不擅自播放。

## 来源跳转与 QueryBack（I4）

首次查看来源时冻结原提问现场：书籍、CFI/offset、正文布局锚、会话/消息 ID、面板和滚动位置。点击第二/第三条来源不覆盖最初锚，不堆叠多个返回胶囊。

来源经统一 command gateway 再验书、Evidence 与已读可见范围；“本书本次查看全部”只在其授权视图内允许未读来源，不带入普通 AI 上下文。导航失败原位提示并保留会话。手机自动收起 AI，正文定位高亮；平板右 AI 保持，左正文定位。

页顶 QueryBack 提供“回原阅读处”和“回 AI”明确动作；“回 AI”恢复会话/滚动，不重新发送。正文高亮普通屏可短暂过渡，E-INK 静态边框/底色，无闪烁要求。关闭胶囊不删除会话；切书、来源失效时清理旧返回锚，重建后不串书。

## R4/R6/R7 交互补充

| 编号 | 规格与失败路径 |
| --- | --- |
| R4 翻页 | Slide/Cover/可选仿真/None；E-INK/减少动画锁 None；对开按组翻，禁止跨过末页。动画能力失败直接静态呈现，不延迟持久位置 |
| R4 亮度/书签 | 独立亮度目标 5%–100%，退出恢复系统 override；顶部按钮/可选下拉添加移除书签，确认当前页文字摘要和来源 |
| R6 表格/公式 | 提取局部 HTML/MathML 生成主题化离屏位图 ImageBlock；缓存含内容/主题/宽度 hash；点击放大，失败提供局部原内容/章节 WebView 兜底，不空白跳过 |
| R7 双字体/选词 | CJK/Latin 分别字体与 fallback；本地轻量中文词表辅助选词，不新建网络/AIDL 管线；偏移仍对应原文 |
| R7 自动阅读 | 句长自适应、0.5x–3.0x；暂停集合 `TOOLBAR/LIFECYCLE/SELECTION/DIALOG/AI_SHEET/LOOKUP`；任一存在暂停，全部清除才可恢复用户已开启的自动阅读 |
| R8 内核 | 长期可选换断行算法，仍保留缓存/来源/动作/AI 边界，不成为当前 UI 实施前置 |

## 多引擎/多端/E-INK 一致性矩阵

表中为目标语义，不能表示平台能力已经完成。iOS runtime 和多引擎逐项设备验收仍待后续完成。

| 行为 | Android 原生 Canvas | 本地 WebView 兜底 | iOS/iPadOS 目标 | E-INK 规则 |
| --- | --- | --- | --- | --- |
| 来源与恢复 | 章节/UTF-16 + CFI 映射 | DOM Range/CFI 验证 | 同共享来源语义，原生排版适配 | 静态定位、字符锚不降级 |
| 选区/扩选 | 显式按钮为默认，手柄调范围 | 按 DOM 能力提供显式命令 | 同语义；平台手柄适配 | 显式跨页，无拖边/放大镜 |
| 双层胶囊 | Compose、统一 action | 复用命令与冻结选区 | SwiftUI 等价动作 | 纯色高对比，不必毛玻璃 |
| 高亮/划线 | 五层、行级区间 | DOM 样式适配，不另写表 | 相同来源区间/样式 | 三样式静态、颜色附文字 |
| AI/来源返回 | 同一 gateway 与 QueryBack | 同一 gateway，保留 WebView | 同契约、左书右 AI | 不显示 token 动画/闪烁 |
| 听读 | 按可靠 offset 句级同步 | 仅在 DOM offset 可验证时同步 | 同句块与源锚 | 句级/页级刷新，保留按钮 |
| 双页 | R1 同布局左右两页 | 不支持时明确单页兜底 | iPad 双页目标 | 细中缝、无阴影动画 |

PDF/扫描图/OCR 仅在真实可验证文本能力下提供选区/TTS/AI；缺失能力说明后隐藏对应动作，页图仍可阅读，不伪造正文 Evidence。表中所有来源权限规则无平台例外。

## 实施与验收状态

v0.3：双层胶囊、R1/R2、五图标 Chrome；v0.4：R3–R6、三层仲裁、放大镜、可选拖边、QueryBack、双页与全屏听书；v0.6：R7；R8 长期可选。各阶段都与稳定性并行，不因视觉完成就跳过位置与动作回归。

| 能力 | 当前实现映射 | 状态与剩余边界 |
| --- | --- | --- |
| R1 缓存与页边界 | [NativeReaderPaginationCache.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/reader/NativeReaderPaginationCache.kt)、NativePagedReader | 缓存/原生接入已新增实现；失效、字符锚恢复与性能待新验收 |
| R2 分层与批注 | [ReaderAnnotationDrawing.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/reader/ReaderAnnotationDrawing.kt)、NativePagedReader | 分层/行级批注已实现待验收；完整五层职责、胶囊和撤销联动逐项核验 |
| R5 仲裁与拖边 | ReaderGestureArbiter、ReaderEdgeSelectionController | core 与原生 pointer/手柄/生命周期、圆形放大镜及可选拖边已有实现；真实拖动/边缘/多目标重叠设备验收待完成 |
| 双页/AI 协作 | [ReaderPageSpread.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/reader/ReaderPageSpread.kt)、NativePagedReader、ReadingWorkspaceHost | 部分实现；响应式切换、字符锚和双页点击边界待新验收 |
| R3/TTS 听读 | [NarrationPlaybackPosition.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/NarrationPlaybackPosition.kt)、NativeNarrator、NarrationPlaybackService、NarrationPlayerScreen | 新增位置/播放/界面代码，完整听读部分实现；真实播放、跨页与音频焦点待新验收 |

复用入口：[SelectionRangeController.kt](../../android/core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/SelectionRangeController.kt)、[SelectionRangeControls.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/reader/SelectionRangeControls.kt)、[NativePagedReader.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/reader/NativePagedReader.kt)、[ReaderScreen.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/reader/ReaderScreen.kt)。这些入口存在不代表目标责任链/双层胶囊/听读验收已经通过。

集成后需定向证明：手势单一 owner 与 cancel、选区版本、显式扩选/终点校验、拖边默认关闭/E-INK 禁用、三样式改色合并与撤销、跨页句去抖、重复来源返回、旋转/调宽/后台恢复、普通阅读零模型及四语可访问性。真实设备、截图、播放与当前构建的验证结果由独立验收报告记录；不得以 core 分项实现或历史报告预签整包通过。
