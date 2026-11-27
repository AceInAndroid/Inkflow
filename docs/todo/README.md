# Inkflow 正式规划与专项索引

更新：2026-11-27。总计划已批准，**批准不等于实现或验收**。以[总体路线](inkflow-master-roadmap.md)为唯一排期与状态入口，旧专项只细化需求，不覆盖原生契约。未列真实验收证据的阶段不得标完成。

## 四份正式规范

| 文档 | 内容 | 当前状态 |
| --- | --- | --- |
| [总体路线](inkflow-master-roadmap.md) | v0.2 基线、五主线、R1–R8、v0.3–v1.0 依赖/DoD | 已批准；各阶段尚未整体验收 |
| [UI 结构与规格](inkflow-ui-structure-and-specs.md) | 首页｜书架｜我的；四首页一次交付、双层皮肤、阅读 Chrome/听书/双页 | v0.3/v0.4 目标，正在并行实现，待验收 |
| [AI 交互与 Skill](inkflow-ai-interaction-and-skills-spec.md) | 问书/引用、六 Agent、Markdown/JSON Skill、范围授权和全部成果 | 原生基础已有；GenerationAuthorization 初步实现待测试，完整规格待交付 |
| [阅读交互](inkflow-reader-interaction-spec.md) | 仲裁、显式扩选、胶囊、放大镜、TTS、QueryBack、多引擎矩阵 | v0.3/v0.4/v0.6 分项待实现/待验收 |

## 专项与原生边界

| 文档 | 归属 | 状态解释 |
| --- | --- | --- |
| [阅读引擎 R1–R8](inkflow-reader-engine-v2.md) | R1/R2 v0.3；R3–R6 v0.4；R7 v0.6；R8 长期可选 | 编号保留，既有分页不等于全 R 完成 |
| [主动阅读教练](ai-active-reading-coach.md) | v0.5 完整教练/复习，v0.6 偏好/生态 | 旧端 Phase 完成声明已改为原生状态；原生完整闭环待实现 |
| [闭环剩余工作](reading-loop-closure-gaps.md) | v0.5 归档/行动/悬念/归因，v0.6 Profile | 部分投影/resolver 已有，完整闭环待验收 |
| [原生 AI 契约](../architecture/native-ai-contract.md) | 注册请求/任务/Evidence/动作/投影与 scope 扩展 | 强制边界；实现初步完成不是验收 |
| [原生 P1 说明](../architecture/native-reading-p1.md) | 既有笔记整理、Atlas、局部成果与下一行动 | 复用现状和明确限制 |
| [选区产品契约](../design/selection/DESIGN.md) | 冻结现场、就地工具、原生目标补充 | 与阅读交互规范双向配套 |
| [根产品决策](../../DESIGN.md) | 当前原生决策优先级与参考历史 | 新规格覆盖旧端相冲突默认布局 |
| [AI 系统图](../architecture/ai-system-map.md) / [架构](../architecture/ai-architecture.md) / [功能地图](../architecture/feature-map.md) | 原生前置边界与 Flutter 参考索引 | 按平台区分完成事实 |

## 验收基线与待办

- [迁移说明](../../android/NATIVE_MIGRATION.md)、[迁移计划](../../android/NATIVE_MIGRATION_PLAN.md)：原生 v0.2 的实现和历史验收。
- [最新持续阅读/生成记录](../../android/CONTINUED_TEST_2026-11-26.md)：30 分钟阅读与最终全量回归，也保留全书入口缺口及真实结构化生成失败。
- [实机/局域网模型](../../android/LIVE_TEST_2026-11-26.md)、[中英日韩专项](../../android/LOCALIZATION.md)、[iOS 验证限制](../../ios-native/VALIDATION.md)：仅各自范围有效。

本次并行集成的新代码，包括 GenerationAuthorization 和偏好 v5，需新的定向/兼容验收；不要复用旧构建结果宣布新阶段通过。稳定性与 UI 并行；显式扩选默认，拖边可选且 E-INK 禁用；全书生成与本书本次查看全部独立授权。
