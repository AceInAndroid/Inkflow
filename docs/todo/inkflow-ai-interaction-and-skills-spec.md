# Inkflow AI 交互、Agent 与 Reading Skill 规范

当前构建、失败修复与设备覆盖见[2026-11-27 实施验收记录](../../android/IMPLEMENTATION_2026-11-27.md)。分项通过不能替代整阶段放行。

更新：2026-11-27。状态：设计已批准并进入实现；Scope、双格式 Skill、本机绑定、WiFi 确认队列与增量同步已有新增代码，完整流程部分实现待新验收；对话/十 Skill/任务继续作为复用基线。参见[总体路线](inkflow-master-roadmap.md)、[UI 规范](inkflow-ui-structure-and-specs.md)、[阅读交互](inkflow-reader-interaction-spec.md)。

## 单一执行链与能力边界

强制遵循[原生 AI 契约](../architecture/native-ai-contract.md)，开发前对照[系统图](../architecture/ai-system-map.md)、[架构](../architecture/ai-architecture.md)、[功能地图](../architecture/feature-map.md)及[根产品决策](../../DESIGN.md)。这些文档保留 Flutter 参考实现的地方不能作为原生功能已完成的证据。

```mermaid
flowchart LR
  UI[明确发送或确认任务] --> Q[注册 AiRequest]
  Q --> C[AiContextAssembler / 冻结现场与授权]
  C --> G[AiRequestGateway / 角色 重试 用量]
  G --> T[既有 NativeAiClient]
  T --> E[结构校验与 Evidence]
  E --> M[授权 mutation / NativeReadingActions]
  M --> D[v22 既有数据与 checkpoint]
  D --> P[安全领域投影]
  P --> UI
```

Agent 是注册的阅读职责和工具选择策略；Skill 是方法说明；workload 是请求执行/角色边界；任务是持久化生命周期；投影是读取边界。六 Agent 不意味着六个独立 Provider。页面不拼私有 prompt、不直接调用 transport、不直写 AI 表、不另建任务/同步/剧透规则。扩展点缺失先补可复用契约再接功能。

普通翻页、选字、开面板、切 Agent/Skill、切语言、查本地成果、恢复会话均 **零模型调用**。本地工具只读已授权来源；网络检索/翻译/在线音频和持久写入分别须有明确动作。

## AI 问书与响应式布局

参考批准计划中的 AI 录屏 `43371dd0…mp4`（01:19）；以下时间点为批准的产品参考，视觉一致性须在对应真实构建上独立验收。

- 手机正文保持单页，`AI` 圆钮或选区问 AI 打开约 90% 高单页 Sheet。标题“AI 问书”、书名副标题、左上 `v` 收起；历史、更多和方法入口不占满标题。收起保留草稿、会话、来源与滚动；输入法打开仍能看到问题和发送/停止。
- ≥840dp 可用宽度按[UI 约束](inkflow-ui-structure-and-specs.md)左书右 AI，面板目标 380–440dp，保留 renderer 与面板实例；窄分屏回 Sheet。切布局不重发请求、不丢流式片段。
- 输入框上方横向 3–4 枚情境快捷胶囊；左侧一枚“问书助手 · 通用阅读”选择器切 Agent/Skill；药丸输入框、钴蓝 `↑` 发送，流式时显示 `■` 停止。打开不自动键盘、选区作为可移除引用不覆盖草稿。
- 空态、缺供应商、缺来源、加载、部分回答、停止、失败与重试均在原位反馈。失败保留问题与已收到内容。关闭普通短对话停止生成；持久任务按 scheduler 暂停/继续/取消，不随面板关闭丢失。

## 执行轨迹、回答与可验证来源

| 参考片段 | 待交付 UI | 数据约束 |
| --- | --- | --- |
| 00:09 / 00:17 / 00:34 / 01:05 | 淡灰折叠卡，“思考中…”→“已完成”，本地检索/资料读取步骤 | 仅真实状态、工具事件、简短可审计摘要；不显示或要求模型隐藏思维链 |
| 00:11 / 00:24–00:30 / 01:12–01:19 | Markdown 分段、加粗、内联 `①②③`，折叠“书籍引用 · N” | 角标与经过验证的 Evidence 一一对应；编号是展示序号，底层来源 ID 稳定 |
| 00:13 对比 01:01 | 随章节/安全范围刷新的追问卡 | 本地失效重建或同次回答附带，不为翻页额外请求模型 |

透明卡可沿用“思考中”作为忙碌状态，但不伪造“深度思考”时长、工具调用或检索结果。未执行工具就不展示该步骤；若供应商没有可展示摘要，显示“正在检索本书/正在整理回答”等真实阶段即可。E-INK 仅在状态变化刷新，不打字机逐 token 动画。

引用项显示章节、原文摘录、必要来源身份与可用状态；章节 href、CFI/UTF-16 范围、exact quote、`visibleFromProgress` 均由 resolver/投影复核。原文、推断、外部常识分开表达；外部内容未查证须标待核实。未验证、未知来源、越界证据不生成可点击角标。来源丢失时保留回答和文字解释，不伪造定位。

点击角标/来源走统一 reader command/source gateway：手机收起 AI、跳到原文、静态或短暂高亮并出现 QueryBack；宽屏只跳左侧正文，右面板保持。连续查看多个来源保留最初提问处。返回可选“回原阅读处”“回 AI 会话”，见[阅读交互](inkflow-reader-interaction-spec.md)。

回答操作为“存入笔记”“复制”“分享卡片”；存入笔记经明确授权动作验证并给出 30 天冲突安全撤销。分享前预览内容/来源/可见范围，不把授权或凭据带出。Markdown 不能执行脚本或加载未授权远端资源。

## 六个内置阅读 Agent

以下 ID 为批准的产品目标契约；[ReadingAgentRegistry.kt](../../android/core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/ai/ReadingAgentRegistry.kt) 已新增六项目录，当前实现 ID 与目标 ID 的映射、可用状态和完整工具路由仍需核对验收，不能由目录存在推定完整能力可用。十项 Reading Skill 与 Agent 注册分开。工具名描述目标语义，接入既有受控工具，不因此创建私有执行链。

| 稳定 Agent ID | 名称/职责 | 本地工具语义 | 快捷胶囊示例 |
| --- | --- | --- | --- |
| `agent.book_qa` | 默认问书助手，通用理解 | `search_read_chapters`、`load_book_wiki`、`load_user_notes` | 章节总结、核心观点、背景解读 |
| `agent.character_atlas` | 人物与脉络，小说/历史/传记 | `query_story_atlas`、已读原文检索 | 人物介绍、关系梳理、前情回顾 |
| `agent.context_fact` | 背景与考据，经典/社科/历史 | 已读检索；区分本书陈述与外部常识 | 背景解读、典故释义、待核实清单 |
| `agent.argument_critic` | 论证与思辨 | 已读检索、`load_user_highlights` | 论证拆解、隐含前提、反例思考 |
| `agent.language_tutor` | 外语精读 | `lookup_offline_dict`、明确请求的 `translate_selection`、词汇草稿 | 自然翻译、词汇精讲、长难句拆解 |
| `agent.active_coach` | 主动阅读教练完整闭环在 v0.5 | `load_reading_guide`、`load_difficulties`、显式 `generate_quiz_choices` | 检视向导、本章自测、拆解难点 |

Agent 选择可按书籍元数据/已读资料和用户绑定作本地推荐；切换不发送。未知 Agent 不自动执行，已有记录保留并提示选择受支持项。后台不能替用户答题或确认笔记；词汇工具先生成草稿，确认后才入库。考据联网默认关，本轮显式允许才走受控联网能力，不能假装本地来源。

## 进度感知追问：零额外请求

冷启动由章节标题、当前安全实体/Wiki 投影、选中的 Agent 与 Skill `quick_prompts` 本地组合最多三条问题。没有安全素材用通用问题，不扫描未读正文、不臆造人物事件。

续聊可在**同一次用户已授权的回答**末尾结构化附带 2–3 条追问；无法解析则保留本地提示，不为补提示再请求。提示记录书籍、章、范围和上下文版本；切章/回退进度本地失效，重新打开时改为当前安全范围的问题。未知/超范围建议隐藏。点击问题才等同明确发送，点击前不调用模型。

## Reading Skill：复用与扩展

现有[ReadingSkillRegistry.kt](../../android/core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/ai/ReadingSkillRegistry.kt)已提供 `id/title/summary/instructions`、`custom.*` 验证和摘要/完整加载接口。保留十个稳定内置 ID：

| ID | 名称 | ID | 名称 |
| --- | --- | --- | --- |
| `reading.general` | 通用阅读 | `reading.socratic` | 苏格拉底教学 |
| `reading.argument` | 论证拆解 | `reading.characters` | 人物追踪 |
| `reading.history` | 史料核查 | `reading.language` | 外语精读 |
| `reading.recap` | 章节回顾 | `reading.concepts` | 概念梳理 |
| `reading.critical` | 批判阅读 | `reading.reflect` | 阅读反思 |

目标元数据 `category/recommended_agent/quick_prompts/output_format` 已有 Registry/SkillCodec 对应扩展；双格式解析、预览和导入导出已新增实现，字段映射、旧格式及未知字段兼容待新验收。旧 JSON 缺字段时有默认值。不能重命名内置 ID、用新增 Agent ID 替换既有 Skill ID。

### 文件与格式

同时支持推荐的人可读 `.md`/`.skill.md`（YAML frontmatter + 正文 instructions）及兼容 `.json` 单条/集合。UTF-8，单文件不超过 **64 KiB**，单 Skill instructions 不超过 **16,000 字符**；ID 不超过现有 100 字符。解析器限制嵌套/别名展开和字段规模，不运行 YAML 自定义标签、脚本、命令或远端 include。

以下为产品层 Markdown 互操作示例；双格式编解码已新增实现，示例字段与现有解析器的兼容性仍待验收：

```markdown
---
id: custom.munger_mental_models
title: 芒格思维模型拆解
summary: 用多元思维模型分析已读章节的决策与偏差
category: thinking
recommended_agent: agent.argument_critic
quick_prompts:
  - 提取本章涉及的思维模型
  - 找出作者论证中的潜在盲区
output_format: structured_with_citations
---
先概括已读段落的核心问题，再列出相关模型及可核验原文。
区分原文与推断，不越过用户授权范围，不替用户保存成果。
```

JSON 保留现有 `id/title/summary/instructions` 字段；集合原子验证或逐项预览失败，不能悄悄丢条目。导入预览展示名称、摘要、ID 冲突/未知元数据；用户确认后生效。ID 自动建议规范为 `custom.<slug>`；同 ID 默认不覆盖，提供重命名/显式替换。未知可选元数据尽量保留便于导出，未知可执行能力拒绝。

### 四入口与绑定

1. 本地 SAF 文件导入：多条预览，明确区分书籍 Markdown 和 Skill Markdown。
2. 粘贴文本/内置编辑器：摘要和完整说明分开，保存前同一 validator。
3. 无线传书页拖入：明确“阅读 Skill”类别和手机确认，不把收到文件当已授权执行；单文件/集合与本地入口同校验。
4. 导出/分享：`.skill.md` 或 `.json`，可预览；不携带 Provider 地址、密钥、用户对话、书籍正文或授权记录。

支持全局默认和单书绑定。选择优先级为本轮明确选择 > 本书绑定 > 全局默认 > `reading.general`；切书恢复本书选择，删除 Skill 时保留可解释的缺失绑定并回退默认，不崩溃。列表/胶囊只读 summary + quick_prompts；仅用户发送时由 assembler 加载完整 instructions。

Skill 不是权限：不能改变系统/进度规则、引入 Provider、静默联网、工具脚本或直接写库。恶意提示作为不可信方法文本处理，仍受系统权限约束。JSON 兼容、两级加载、无效 ID、重复冲突、未知字段及无模型副作用均须验证。

### 数据与同步

自定义 Skill 定义及元数据复用 v5 偏好备份，读取 v1–v4 保持兼容；全局默认/单书绑定为本机选择，不进入备份或同步。现有 ZIP/WebDAV 与 single-flight 边界保持不变，不新建独立 Skill 云服务/业务表。[NativeSkillSyncController.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/NativeSkillSyncController.kt) 已通过 NativeIncrementalSyncStore 的既有 artifact record/envelope 接入 WebDAV 增量同步；[SkillTransferInbox.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/transfer/SkillTransferInbox.kt) 和无线传书页面已有解析、预览及显式确认队列。以上属于部分实现待新验收，未知版本冲突保留和确认重试幂等仍需处理，不能因代码存在就标完整同步链路通过。按稳定 ID/版本判断冲突，默认保留副本或明确替换，禁止静默覆盖本地编辑；WiFi 接收不得直接安装或执行。凭据/设备显示策略/临时查看授权不导出。

## 生成范围、任务和全部成果

以[GenerationAuthorization 契约](../architecture/native-ai-contract.md#generation-scope-extension-contract-2026-11-27)为权威实现要求。本节给出产品流程；GenerationAuthorization、三 scope 及相关任务/查看流程已新增实现，范围隔离、恢复、来源变化与完整交互仍待当前集成版本的新验收。

| 范围 | 确认内容 | 数据及复核 |
| --- | --- | --- |
| `READ_BOUNDARY` | 当前已读/已完成章节清单与安全边界 | 冻结精确 cursor/章节；旧 payload v1 默认此范围 |
| `SELECTED_CHAPTERS` | 用户选择章节，单独提示包含的未读内容 | 清单不可静默扩展；未读也需要明确生成授权 |
| `WHOLE_BOOK` | 全部可处理章节、未读覆盖/剧透提示、来源发送、Provider、规模/批次数 | 一次确认冻结任务，分批处理，不要求逐章重复确认 |

授权绑定 bookId/taskId/有序章节清单/书与章节来源 hash/明确同意，写入 payload v2。v1 无 discriminator 按原捕获已读边界恢复；未知版本不执行、不丢数据。新增 `AiRequest` 范围授权仅注册生成 workload 可用；聊天/解释/翻译/笔记整理不能借此扩大正文输入。

在读源前、每次模型调用/纠正前、提交结果前重复校验：书/任务、授权状态、清单/hash、范围、任务未取消。恢复原任务可复用相同冻结授权，但必须用户点继续；改变源/清单/任务需重新确认。变更阅读位置不伪造 100% 进度，也不让全书授权扩大普通对话历史。

默认本书资料、Wiki、Atlas、搜索和 AI 上下文均按已读投影。单独的“查看全部成果”说明含未读信息，确认授予**本书、本次查看、本机**访问；仅能看已有生成内容及其来源，不生成、不写阅读进度、不改变默认安全范围、不同步授权。退出查看/换书/重启清除；返回已读模式立即重建投影。即使来源跳转由用户改变了当前页，也不能把该操作解释为整书已读。

任务卡可在本书资料及任务入口查看，显示类型、范围、已完成/总批次、最后 checkpoint、暂停/继续/取消/错误。没有实际总页数或账单单价时只显示估计规模，不能造“已节省 X 元”。

Wiki/Atlas 结构无效最多由**同供应商一次 schema 纠正**，沿用同授权/原文/Evidence；不可改供应商反复补 JSON、扩大范围或捏造证据。再次失败保留 checkpoint/已存成果，记录失败单元，原位重试。checkpoint 与结果原子提交；恢复跳过已完成 source hash。transport 的有限密钥重试与 schema 纠正次数分开统计，已流出片段不能靠 fallback 重复输出。

## 深度阅读与阶段衔接

v0.4 注册教练入口与已支持操作，不能展示尚不可执行的“已完成复习”。v0.5 才交付完整[主动教练](ai-active-reading-coach.md)：零键盘检视、四问题、80% 后切章自测的本地提示、难点、知识卡 1/3/7/14/30 天复习、完成归档、下一行动与悬念闭环。打开或达到阈值只提示/更新本地状态；生成题目仍需明确点击。

完整目录 AI 大纲、安全投影与 v0.4 范围任务衔接，不能回到另一套全书生成。v0.6 偏好候选至少三会话证据，用户确认才进入上下文；候选与用户事实分层。

## 原生改造映射与验收

| 现有入口 | 复用/扩展责任 | 当前限制 |
| --- | --- | --- |
| [AiReadingSheet.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/ai/AiReadingSheet.kt) | 问书 UI、流式/来源/方法选择，调用现有服务 | 录屏卡片/角标/六 Agent 新规格未整体验收 |
| [ReadingWorkspaceHost.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ui/ai/ReadingWorkspaceHost.kt) | 保持 reader slot；响应式面板 | 当前已有 380–440dp 调宽与响应式代码，双页/AI 联动部分实现，待新验收 |
| [AiRequestGateway.kt](../../android/core/src/commonMain/kotlin/com/aceinandroid/inkflow/core/ai/AiRequestGateway.kt) | 注册请求、角色、assembler、授权 envelope 与用量 | 只在此共享边界增加范围能力 |
| [NativeBookGenerationService.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ai/NativeBookGenerationService.kt) | 同 scheduler、payload v2、scope/checkpoint/一次纠正 | 历史基线仅已完成章节且无效 JSON 曾失败；现有范围扩展及相关任务代码已新增，待新验收 |
| [NativeReadingActions.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ai/NativeReadingActions.kt) | 保存/接受建议、撤销与版本保护 | 页面不得以范围扩展直接写表 |
| [BookWikiRepository.kt](../../android/app/src/main/kotlin/com/aceinandroid/inkflow/ai/BookWikiRepository.kt) | 已读/本次全部安全查询投影与来源 | 不能以全部成果 grant 改普通模型上下文 |

必须证明注册路由/角色/有限 fallback、任务恢复与未知版本、精确 Evidence/边界、明确写入和撤销、未知 kind 兼容、四语/E-INK，以及普通事件零模型。scope、Skill 导入及来源返回各有最小定向验证，随后适用的两变体/lint/设备验收。真实生成质量、在线检索、TTS、外部服务和视频近似度分别由独立验收报告记录；源码状态不代替当前构建的验收结论，不预签 M4–M7 整包通过。
